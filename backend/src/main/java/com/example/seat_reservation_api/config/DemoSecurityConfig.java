package com.example.seat_reservation_api.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** ローカル操作確認用認証（Cognito不使用固定ユーザー） */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoSecurityConfig {
    @Bean
    JwtDecoder demoJwtDecoder(Environment environment,
            @Value("${app.demo.user-id}") String userId,
            @Value("${app.demo.role}") String role) {
        if (!environment.acceptsProfiles(Profiles.of("local"))
                || environment.acceptsProfiles(Profiles.of("prod", "production"))) {
            throw new IllegalStateException("demo authentication requires the local profile only, not production");
        }
        if (userId.isBlank() || !List.of("USER", "ADMIN").contains(role)) {
            throw new IllegalArgumentException("Set a nonblank demo user ID and USER or ADMIN role");
        }
        return token -> {
            if (!"local-demo".equals(token)) {
                throw new BadJwtException("Invalid demo token");
            }

            return Jwt.withTokenValue(token).header("alg", "none").subject(userId)
                    .claim("cognito:groups", "ADMIN".equals(role) ? List.of("ADMINS") : List.of())
                    .build();
        };
    }
}
