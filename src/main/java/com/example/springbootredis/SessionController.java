package com.example.springbootredis;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    @Value("${server.port:8080}")
    private String port;

    @Value("${HOSTNAME:unknown}")
    private String hostname;

    @GetMapping("/")
    public String index(HttpSession session) {
        if (session.getAttribute("visit-server") == null) {
            session.setAttribute("visit-server", hostname);
        }

        return "<h3>Session Clustering Test</h3>" +
               "현재 응답 중인 서버: " + hostname + "<br>" +
               "세션에 기록된 최초 접속 서버: " + session.getAttribute("visit-server");
    }
}
