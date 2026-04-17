package com.example.springbootredis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {

    @GetMapping("/health")
    public String healthCheck() {
        return "OK"; // ALB는 200 OK 응답만 받으면 정상으로 간주합니다.
    }
}
