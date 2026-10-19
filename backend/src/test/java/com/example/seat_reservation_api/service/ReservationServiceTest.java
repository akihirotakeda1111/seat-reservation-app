package com.example.seat_reservation_api.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.repository.SeatRepository;
import com.example.seat_reservation_api.repository.SeatReservationView;
import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.exception.InvalidSeatException;
import com.example.seat_reservation_api.exception.ReservationNotFoundException;
import java.util.Optional;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    ReservationRepository reservationRepository;

    @Mock
    SeatRepository seatRepository;

    @Spy
    Clock clock = Clock.fixed(Instant.parse("2026-10-09T15:30:00Z"),
            ZoneId.of("Asia/Tokyo"));

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
    @DisplayName("IDで予約取得")
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
    @DisplayName("予約登録")
    class ReserveTest {
        @ParameterizedTest(name = "基準日から{0}日後の予約を保存する")
        @ValueSource(ints = {0, 1, 365})
        void 予約を保存し採番IDを返す(int offsetDays) {
            UUID savedId = UUID.randomUUID();
            LocalDate date = LocalDate.now(clock).plusDays(offsetDays);
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

        @ParameterizedTest(name = "{0}日前の予約は拒否する")
        @ValueSource(ints = {1, 30})
        void 過去日はRepositoryを呼ばず拒否する(int daysAgo) {
            assertThatThrownBy(() -> service.reserve(LocalDate.now(clock).minusDays(daysAgo),
                    "demo-user-1", (short) 1))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(exception.getReason()).isEqualTo("リクエストの内容が不正です。");
                    });
            verifyNoInteractions(seatRepository, reservationRepository);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dateRolloverCases")
        void 日本時間で日付が切り替わる(String description, Instant currentInstant,
                LocalDate reservationDate, boolean allowed) {
            Clock boundaryClock = Clock.fixed(currentInstant, ZoneId.of("Asia/Tokyo"));
            ReservationService boundaryService =
                    new ReservationService(reservationRepository, seatRepository, boundaryClock);

            if (allowed) {
                UUID savedId = UUID.randomUUID();
                when(seatRepository.existsById((short) 1)).thenReturn(true);
                Reservation saved = mock(Reservation.class);
                when(saved.getId()).thenReturn(savedId);
                when(reservationRepository.save(any(Reservation.class))).thenReturn(saved);

                assertThat(boundaryService.reserve(reservationDate, "demo-user-1", (short) 1))
                        .isEqualTo(savedId);
                ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
                verify(reservationRepository).save(captor.capture());
                assertThat(captor.getValue().getReservationDate()).isEqualTo(reservationDate);
            } else {
                assertThatThrownBy(() -> boundaryService.reserve(reservationDate, "demo-user-1", (short) 1))
                        .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                            assertThat(exception.getReason()).isEqualTo("リクエストの内容が不正です。");
                        });
                verifyNoInteractions(seatRepository, reservationRepository);
            }
        }

        static Stream<Arguments> dateRolloverCases() {
            Instant beforeMidnight = Instant.parse("2026-10-09T14:59:59Z");
            Instant atMidnight = Instant.parse("2026-10-09T15:00:00Z");
            LocalDate october9 = LocalDate.of(2026, 10, 9);
            LocalDate october10 = LocalDate.of(2026, 10, 10);
            return Stream.of(
                    Arguments.of("直前：10月9日は当日なので予約可能", beforeMidnight, october9, true),
                    Arguments.of("直後：10月9日は過去日なので予約不可", atMidnight, october9, false),
                    Arguments.of("直前：10月10日は翌日なので予約可能", beforeMidnight, october10, true),
                    Arguments.of("直後：10月10日は当日なので予約可能", atMidnight, october10, true));
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

    @Nested
    @DisplayName("キャンセル")
    class CancelReserveTest {
        @ParameterizedTest(name = "基準日から{0}日後の自分の予約を削除する")
        @ValueSource(ints = {0, 1})
        void 当日と翌日の予約を削除する(int offsetDays) {
            UUID id = UUID.randomUUID();
            String userId = "demo-user-1";
            Reservation reservation = mock(Reservation.class);
            when(reservation.getId()).thenReturn(id);
            when(reservation.getUserId()).thenReturn(userId);
            LocalDate date = LocalDate.now(clock).plusDays(offsetDays);
            when(reservation.getReservationDate()).thenReturn(date);
            when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));
            when(reservationRepository.deleteReservationById(id)).thenReturn(1);

            service.cancelOwnReservation(id, userId);

            var order = inOrder(reservationRepository);
            order.verify(reservationRepository).findById(id);
            order.verify(reservationRepository).deleteReservationById(id);
            verifyNoMoreInteractions(reservationRepository);
        }

        @ParameterizedTest(name = "{0}日前の予約は400でDELETEを呼ばない")
        @ValueSource(ints = {1, 30})
        void 過去日の予約は削除しない(int daysAgo) {
            UUID id = UUID.randomUUID();
            String userId = "demo-user-1";
            Reservation reservation = new Reservation(
                    LocalDate.now(clock).minusDays(daysAgo), (short) 1, userId);
            when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));

            assertThatThrownBy(() -> service.cancelOwnReservation(id, userId))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(exception.getReason()).isEqualTo("過去の予約はキャンセルできません。");
                    });

            verify(reservationRepository).findById(id);
            verify(reservationRepository, never()).deleteReservationById(any());
            verifyNoMoreInteractions(reservationRepository);
        }

        @Test
        void 他人の予約は削除しない() {
            UUID id = UUID.randomUUID();
            Reservation reservation = new Reservation(
                    LocalDate.now(clock).plusDays(1), (short) 1, "demo-user-1");
            when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));

            assertThatThrownBy(() -> service.cancelOwnReservation(id, "demo-user-2"))
                    .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(exception.getReason()).isEqualTo("指定された予約はキャンセルできません。");
                    });

            verify(reservationRepository).findById(id);
            verify(reservationRepository, never()).deleteReservationById(any());
            verifyNoMoreInteractions(reservationRepository);
        }

        @Test
        void 取得時に予約が存在しなければ削除しない() {
            UUID id = UUID.randomUUID();
            when(reservationRepository.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelOwnReservation(id, "demo-user-1"))
                    .isInstanceOf(ReservationNotFoundException.class);

            verify(reservationRepository).findById(id);
            verify(reservationRepository, never()).deleteReservationById(any());
            verifyNoMoreInteractions(reservationRepository);
        }

        @Test
        void 取得後に削除対象がなくなれば予約なしの例外を返す() {
            UUID id = UUID.randomUUID();
            String userId = "demo-user-1";
            Reservation reservation = mock(Reservation.class);
            when(reservation.getId()).thenReturn(id);
            when(reservation.getUserId()).thenReturn(userId);
            LocalDate date = LocalDate.now(clock);
            when(reservation.getReservationDate()).thenReturn(date);
            when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));
            when(reservationRepository.deleteReservationById(id)).thenReturn(0);

            assertThatThrownBy(() -> service.cancelOwnReservation(id, userId))
                    .isInstanceOf(ReservationNotFoundException.class);

            verify(reservationRepository).findById(id);
            verify(reservationRepository).deleteReservationById(id);
        }

        @ParameterizedTest(name = "{0}の例外を伝播する")
        @ValueSource(strings = {"予約照会時", "予約削除時"})
        void 想定外の例外を発生箇所ごとに伝播する(String failurePoint) {
            UUID id = UUID.randomUUID();
            String userId = "demo-user-1";
            RuntimeException failure = new RuntimeException("内部システムエラー");
            boolean lookupFails = "予約照会時".equals(failurePoint);
            if (lookupFails) {
                when(reservationRepository.findById(id)).thenThrow(failure);
            } else {
                Reservation reservation = mock(Reservation.class);
                when(reservation.getId()).thenReturn(id);
                when(reservation.getUserId()).thenReturn(userId);
                LocalDate date = LocalDate.now(clock);
                when(reservation.getReservationDate()).thenReturn(date);
                when(reservationRepository.findById(id)).thenReturn(Optional.of(reservation));
                when(reservationRepository.deleteReservationById(id)).thenThrow(failure);
            }

            assertThatThrownBy(() -> service.cancelOwnReservation(id, userId)).isSameAs(failure);

            verify(reservationRepository).findById(id);
            if (lookupFails) {
                verify(reservationRepository, never()).deleteReservationById(any());
            } else {
                verify(reservationRepository).deleteReservationById(id);
            }
            verifyNoMoreInteractions(reservationRepository);
        }
    }
}
