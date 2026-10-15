package com.example.seat_reservation_api.integration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 結合テストで適用する時計設定 */
@TestConfiguration(proxyBeanMethods = false)
class ReservationIntegrationTestConfig {
    // 日付境界を固定：日本時間の当日は2026-10-11となる。
    static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-10T15:30:00Z"), ZoneId.of("Asia/Tokyo"));

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        // 専用PostgreSQLを定義し、接続情報は@ServiceConnectionでアプリへ渡す。
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    @Primary
    Clock integrationClock() {
        // @PrimaryによりアプリのClockより優先し、日付判定を実行日時に依存させない。
        return FIXED_CLOCK;
    }
}
