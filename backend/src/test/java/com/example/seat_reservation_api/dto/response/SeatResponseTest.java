package com.example.seat_reservation_api.dto.response;

import com.example.seat_reservation_api.entity.Reservation;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SeatResponseTest {

    @Test
    void from_予約がなければAVAILABLEで予約IDを返さない() {
        Short seatNumber = 1;
        String userId = "demo-user-1";

        SeatResponse result = SeatResponse.from(seatNumber, null, userId);

        assertThat(result.seatNumber()).isEqualTo(seatNumber);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.reservationId()).isNull();
    }

    @Test
    void from_所有予約ならMINEと予約IDを返す() {
        UUID id = UUID.randomUUID();
        Short seatNumber = 1;
        String userId = "demo-user-1";

        Reservation reservation = mock(Reservation.class);
        when(reservation.getUserId()).thenReturn(userId);
        when(reservation.getId()).thenReturn(id);

        SeatResponse result = SeatResponse.from(seatNumber, reservation, userId);

        assertThat(result.seatNumber()).isEqualTo(seatNumber);
        assertThat(result.status()).isEqualTo("MINE");
        assertThat(result.reservationId()).isEqualTo(id);
    }

    @Test
    void from_非所有予約ならOCCUPIEDで予約IDを返さない() {
        Short seatNumber = 1;
        String userId = "demo-user-1";
        String otherUserId = "demo-user-2";
        UUID otherReservationId = UUID.randomUUID();

        Reservation reservation = mock(Reservation.class);
        when(reservation.getUserId()).thenReturn(otherUserId);
        when(reservation.getId()).thenReturn(otherReservationId);

        SeatResponse result = SeatResponse.from(seatNumber, reservation, userId);

        assertThat(result.seatNumber()).isEqualTo(seatNumber);
        assertThat(result.status()).isEqualTo("OCCUPIED");
        assertThat(result.reservationId()).isNull();
    }
}
