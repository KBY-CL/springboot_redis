# AWS EC2 + Docker Redis 세션 클러스터링

> Amazon Linux 2023 환경에서 Docker를 활용하여 Spring Boot 서버 2대와 Redis 1대, Nginx를 구성하고 서버 간 세션이 공유(Clustering)되는지 확인하는 실습

---

## 1. 기술 스택

| 구분 | 내용 |
| --- | --- |
| 인프라 | AWS EC2 (Amazon Linux 2023) |
| 런타임 | Docker, Docker Compose |
| 언어/프레임워크 | Java 21, Maven, Spring Boot 3.5.13 |
| 주요 라이브러리 | Spring Web, Spring Data Redis, Spring Session Redis, Lombok |
| 웹서버 | Nginx (리버스 프록시 + 로드밸런서) |

---

## 2. 프로젝트 구조

```
springboot_redis/
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── nginx/
│   └── nginx.conf
└── src/
    ├── main/
    │   ├── java/com/example/springbootredis/
    │   │   ├── SpringbootRedisApplication.java
    │   │   └── SessionController.java
    │   └── resources/
    │       └── application.yaml
    └── test/
        └── java/com/example/springbootredis/
            └── SpringbootRedisApplicationTests.java
```

---

## 3. 주요 파일

### application.yaml

```yaml
spring:
  application:
    name: springboot_redis
  session:
    store-type: redis
  data:
    redis:
      host: redis-server
      port: 6379
```

### SessionController.java

```java
@RestController
public class SessionController {

    @Value("${server.port:8080}")
    private String port;

    @GetMapping("/")
    public String index(HttpSession session) {
        if (session.getAttribute("visit-port") == null) {
            session.setAttribute("visit-port", port);
        }
        return "<h3>Session Clustering Test</h3>" +
               "현재 응답 중인 서버 포트: " + port + "<br>" +
               "세션에 기록된 최초 접속 서버 포트: " + session.getAttribute("visit-port");
    }
}
```

### Dockerfile (멀티스테이지)

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
COPY --from=builder /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

> Stage 1에서 Maven으로 JAR 빌드 → Stage 2에서 JAR만 복사해 실행
>
> EC2에 JDK 설치 불필요, Docker만 있으면 됨

### docker-compose.yml

```yaml
version: '3.8'
services:
  redis-server:
    image: redis:latest
    container_name: redis-server
    ports:
      - "6379:6379"
    networks:
      - session-net

  app-1:
    build: .
    container_name: app-1
    environment:
      - SERVER_PORT=8080
    depends_on:
      - redis-server
    networks:
      - session-net

  app-2:
    build: .
    container_name: app-2
    environment:
      - SERVER_PORT=8080
    depends_on:
      - redis-server
    networks:
      - session-net

  nginx:
    image: nginx:latest
    container_name: nginx
    ports:
      - "80:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/conf.d/default.conf
    depends_on:
      - app-1
      - app-2
    networks:
      - session-net

networks:
  session-net:
    driver: bridge
```

### nginx/nginx.conf

```nginx
upstream spring-apps {
    server app-1:8080;
    server app-2:8080;
}

server {
    listen 80;

    location / {
        proxy_pass http://spring-apps;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $http_x_forwarded_proto;
        proxy_set_header Cookie $http_cookie;
        proxy_pass_header Set-Cookie;
    }
}
```

---

## 4. 전체 아키텍처

```
Client
  ↓ HTTP (80)
EC2 Public IP → Nginx (라운드로빈 로드밸런싱)
  ↓ HTTP (8080)
app-1 / app-2
  ↓
Redis (세션 공유)
```

---

## 5. AWS 구성

### 보안 그룹

| 대상 | 인바운드 | 허용 출처 |
| --- | --- | --- |
| EC2 SG | 80 | 0.0.0.0/0 |
| EC2 SG | 22 | 내 IP |

---

## 6. EC2 배포 순서

### Docker 설치 (최초 1회)

```bash
sudo dnf install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user
# 재접속 필요

# docker compose, buildx 설치
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
sudo sed -i 's/$releasever/9/g' /etc/yum.repos.d/docker-ce.repo
sudo dnf clean all
sudo dnf install -y docker-compose-plugin --setopt=install_weak_deps=False

# buildx 수동 설치 (0.17.0 이상)
sudo curl -SL https://github.com/docker/buildx/releases/download/v0.17.1/buildx-v0.17.1.linux-amd64 \
  -o /usr/libexec/docker/cli-plugins/docker-buildx
sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx

dnf install -y git
```

### 배포

```bash
git clone https://github.com/KBY-CL/springboot_redis.git
cd springboot_redis
docker compose up --build -d
```

### 업데이트 시

```bash
git pull
docker compose up --build -d
```

### 확인

```bash
docker ps  # 컨테이너 4개 확인 (redis, app-1, app-2, nginx)
```

---

## 7. 테스트 시나리오

1. 브라우저에서 `http://[EC2-Public-IP]` 접속
2. 최초 접속한 서버 포트가 세션에 저장됨
3. 새로고침 반복 → Nginx가 app-1 / app-2 번갈아 응답
4. **서버가 바뀌어도 세션 기록 포트가 유지되면 클러스터링 성공**

### Redis 데이터 직접 확인

```bash
docker exec -it redis-server redis-cli
keys *
```

`spring:session:sessions:...` 형태의 키가 생성되어 있는지 확인합니다.
