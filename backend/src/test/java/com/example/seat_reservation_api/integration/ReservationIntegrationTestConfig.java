package com.example.seat_reservation_api.integration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class ReservationIntegrationTestConfig {
    static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-10T15:30:00Z"), ZoneId.of("Asia/Tokyo"));

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @Primary
    Clock integrationClock() {
        return FIXED_CLOCK;
    }
}
