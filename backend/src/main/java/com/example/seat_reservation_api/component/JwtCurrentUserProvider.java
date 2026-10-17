package com.example.seat_reservation_api.component;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    private JwtAuthenticationToken currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken token
                && token.isAuthenticated()) {
            return token;
        }

        throw new AuthenticationCredentialsNotFoundException(
                "JWT authentication is required.");
    }

    @Override
    public String getUserId() {
        return currentAuthentication().getToken().getSubject();
    }
}
