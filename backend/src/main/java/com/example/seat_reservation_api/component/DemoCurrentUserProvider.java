package com.example.seat_reservation_api.component;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("local")
public class DemoCurrentUserProvider implements CurrentUserProvider {

    private final HttpServletRequest request;

    public DemoCurrentUserProvider(HttpServletRequest request) {
        this.request = request;
    }

    @Override
    public String getUserId() {
        String userId = request.getHeader("X-Demo-User");

        if (!"demo-user-1".equals(userId) && !"demo-user-2".equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "リクエストの内容が不正です。");
        }

        return userId;
    }
}
