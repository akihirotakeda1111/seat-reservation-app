package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.repository.ReservationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

final class ReservationIntegrationTestSupport {
    private final ReservationRepository reservations;
    private final JdbcTemplate jdbc;

    ReservationIntegrationTestSupport(ReservationRepository reservations, JdbcTemplate jdbc) {
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    void assertSeatMaster() {
        assertThat(jdbc.queryForList("select seat_number from seats order by seat_number",
                Integer.class)).containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 30).boxed().toList());
    }

    UUID save(LocalDate date, int seat, String user) {
        return reservations.saveAndFlush(new Reservation(date, (short) seat, user)).getId();
    }

    List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("select * from reservations order by id");
    }

    void assertStoredReservation(UUID id, LocalDate date, int seat, String user) {
        Map<String, Object> row = jdbc.queryForMap("select * from reservations where id = ?", id);
        assertThat(row.get("reservation_date")).isEqualTo(java.sql.Date.valueOf(date));
        assertThat(((Number) row.get("seat_number")).intValue()).isEqualTo(seat);
        assertThat(row.get("user_id")).isEqualTo(user);
        assertThat(row.get("created_at")).isNotNull();
    }
}
