package com.example.seat_reservation_api.config;

import com.example.seat_reservation_api.component.JwtCurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;

import static org.assertj.core.api.Assertions.*;

class DemoSecurityConfigTest {
    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMIN"})
    void fixedIdentityAndAuthorities(String role) {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local", "demo");
        var decoder = new DemoSecurityConfig().demoJwtDecoder(environment, "demo-owner", role);
        var authentication = new SecurityConfig().jwtAuthenticationConverter().convert(decoder.decode("local-demo"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            assertThat(new JwtCurrentUserProvider().getUserId()).isEqualTo("demo-owner");
            assertThat(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))
                    .isEqualTo(role.equals("ADMIN"));
            assertThatThrownBy(() -> decoder.decode("arbitrary-user-or-role")).isInstanceOf(BadJwtException.class);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void rejectsNonLocalAndProduction() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("demo");
        assertThatThrownBy(() -> new DemoSecurityConfig().demoJwtDecoder(environment, "user", "USER"))
                .isInstanceOf(IllegalStateException.class);
        environment.setActiveProfiles("local", "demo", "production");
        assertThatThrownBy(() -> new DemoSecurityConfig().demoJwtDecoder(environment, "user", "USER"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidConfiguration() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("local", "demo");
        assertThatThrownBy(() -> new DemoSecurityConfig().demoJwtDecoder(environment, " ", "USER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DemoSecurityConfig().demoJwtDecoder(environment, "user", "INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
