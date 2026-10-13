package com.example.seat_reservation_api.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.repository.SeatRepository;
import com.example.seat_reservation_api.repository.SeatReservationView;
import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.exception.InvalidSeatException;
import java.util.Optional;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    ReservationRepository reservationRepository;

    @Mock
    SeatRepository seatRepository;

    @InjectMocks
    ReservationService service;

    @Test
    void getSeatsWithReservation_座席マスタ全件と予約情報を結合して返す() {
        LocalDate date = LocalDate.of(2026, 10, 10);

        SeatReservationView row = mock(SeatReservationView.class);
        List<SeatReservationView> expected = List.of(row);

        when(reservationRepository.findSeatsWithReservation(date)).thenReturn(expected);
        List<SeatReservationView> actual = service.getSeatsWithReservation(date);

        assertThat(actual).containsExactly(row);
        verify(reservationRepository).findSeatsWithReservation(date);
    }

    @Nested
    class GetReservationByIdTest {
        @Test
        void 予約が存在すれば結果を返す() {
            UUID id = UUID.randomUUID();
            LocalDate date = LocalDate.of(2026, 10, 1);
            String userId = "demo-user-1";
            Short seatNumber = 1;
            Reservation reservation = new Reservation(date, seatNumber, userId);

            when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));

            Reservation result = service.getReservationById(id);

            assertThat(result).isSameAs(reservation);
            verify(reservationRepository).findById(id);
        }

        @Test
        void 予約が存在しなければNullを返す() {
            UUID id = UUID.randomUUID();
            when(reservationRepository.findById(id)).thenReturn(Optional.empty());

            Reservation result = service.getReservationById(id);

            assertThat(result).isNull();
        }
    }

    @Nested
    class ReserveTest {
        @Test
        void 予約を保存し採番IDを返す() {
            UUID savedId = UUID.randomUUID();
            LocalDate date = LocalDate.of(2026, 10, 1);
            String userId = "demo-user-1";
            Short seatNumber = 1;

            when(seatRepository.existsById(seatNumber)).thenReturn(true);

            Reservation saved = mock(Reservation.class);
            when(saved.getId()).thenReturn(savedId);
            when(reservationRepository.save(any(Reservation.class))).thenReturn(saved);

            UUID id = service.reserve(date, userId, seatNumber);

            assertThat(id).isEqualTo(savedId);

            ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(captor.capture());
            Reservation actual = captor.getValue();

            assertThat(actual.getUserId()).isEqualTo(userId);
            assertThat(actual.getSeatNumber()).isEqualTo(seatNumber);
            assertThat(actual.getReservationDate()).isEqualTo(date);
        }

        @Test
        void 存在しない座席は例外を返し保存しない() {
            LocalDate date = LocalDate.of(2026, 10, 10);
            String userId = "demo-user-1";
            short seatNumber = Short.MAX_VALUE;

            when(seatRepository.existsById(seatNumber)).thenReturn(false);

            assertThatThrownBy(() -> service.reserve(date, userId, seatNumber))
                    .isInstanceOf(InvalidSeatException.class);

            verify(seatRepository).existsById(seatNumber);
            verify(reservationRepository, never()).save(any(Reservation.class));
        }
    }

    @Test
    void cancelReservation_指定IDの予約を削除する() {
        UUID id = UUID.randomUUID();

        service.cancelReservation(id);

        verify(reservationRepository).deleteById(id);
    }
}
