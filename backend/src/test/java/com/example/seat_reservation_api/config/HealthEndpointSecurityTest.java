package com.example.seat_reservation_api.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthEndpointSecurityTest.ProbeController.class)
@Import({SecurityConfig.class, SecurityErrorConfig.class, HealthEndpointSecurityTest.ProbeController.class,
        HealthEndpointSecurityTest.TestSecurityConfig.class})
class HealthEndpointSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class TestSecurityConfig {}

    @Test
    void allowsAnonymousLoadBalancerHealthCheck() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void keepsApiAuthenticated() throws Exception {
        mvc.perform(get("/api/seats")).andExpect(status().isUnauthorized());
    }

    @Test
    void doesNotExposeOtherActuatorPaths() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    @RestController
    static class ProbeController {
        @GetMapping("/actuator/health")
        String health() { return "ok"; }
        @GetMapping("/api/seats")
        String seats() { return "protected"; }
        @GetMapping("/actuator/env")
        String env() { return "protected"; }
    }
}
