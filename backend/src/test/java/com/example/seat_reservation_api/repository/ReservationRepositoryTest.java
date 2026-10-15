package com.example.seat_reservation_api.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.entity.Seat;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ReservationRepositoryTest {

        @Container
        @ServiceConnection
        static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

        @Autowired
        ReservationRepository reservationRepository;

        @Autowired
        SeatRepository seatRepository;

        @Autowired
        jakarta.persistence.EntityManager entityManager;

        @Nested
        class DeleteReservationByIdTest {
                @Test
                void 指定予約だけを削除して件数を返す() {
                        LocalDate date = LocalDate.of(2026, 10, 10);
                        Reservation target = reservationRepository.saveAndFlush(
                                        new Reservation(date, (short) 1, "demo-user-1"));
                        Reservation remaining = reservationRepository.saveAndFlush(
                                        new Reservation(date, (short) 2, "demo-user-2"));
                        entityManager.refresh(remaining);

                        assertThat(reservationRepository.deleteReservationById(target.getId())).isEqualTo(1);
                        entityManager.clear();
                        assertThat(reservationRepository.findById(target.getId())).isEmpty();
                        assertThat(reservationRepository.findAll()).singleElement()
                                        .usingRecursiveComparison().isEqualTo(remaining);
                        assertThat(reservationRepository.deleteReservationById(target.getId())).isZero();
                }

                @Test
                void 存在しないIDなら0件を返す() {
                        assertThat(reservationRepository.deleteReservationById(java.util.UUID.randomUUID()))
                                        .isZero();
                }
        }

        @Nested
        class FindSeatsWithReservationTest {
                @Test
                void 座席マスタ全件と予約情報を結合して返す() {
                        LocalDate reservationDate = LocalDate.of(2026, 10, 1);
                        Short seatNumber = 1;
                        String userId = "demo-user-1";
                        Reservation reservation = new Reservation(reservationDate, seatNumber, userId);
                        reservationRepository.save(reservation);

                        List<SeatReservationView> result = reservationRepository
                                        .findSeatsWithReservation(reservationDate);
                        List<Short> expectedSeatNumbers = seatRepository
                                        .findAll(Sort.by(Seat::getSeatNumber).ascending()).stream()
                                        .map(Seat::getSeatNumber).toList();

                        assertThat(result).isNotEmpty();
                        assertThat(result).extracting(SeatReservationView::getSeatNumber)
                                        .containsExactlyElementsOf(expectedSeatNumbers);

                        assertThat(result.get(0).getSeatNumber()).isEqualTo(seatNumber);
                        assertThat(result.get(0).getReservation()).isNotNull();
                        assertThat(result.get(0).getReservation().getId())
                                        .isEqualTo(reservation.getId());
                        assertThat(result.get(0).getReservation().getReservationDate())
                                        .isEqualTo(reservation.getReservationDate());
                        assertThat(result.get(0).getReservation().getSeatNumber())
                                        .isEqualTo(reservation.getSeatNumber());
                        assertThat(result.get(0).getReservation().getUserId())
                                        .isEqualTo(reservation.getUserId());
                        assertThat(result.get(0).getReservation().getCreatedAt())
                                        .isEqualTo(reservation.getCreatedAt());
                }

                @Test
                void 座席マスタ全件と予約情報Nullを返す() {
                        LocalDate reservationDate = LocalDate.of(2026, 10, 1);
                        List<SeatReservationView> result = reservationRepository
                                        .findSeatsWithReservation(reservationDate);
                        List<Short> expectedSeatNumbers = seatRepository
                                        .findAll(Sort.by(Seat::getSeatNumber).ascending()).stream()
                                        .map(Seat::getSeatNumber).toList();

                        assertThat(result).isNotEmpty();
                        assertThat(result).extracting(SeatReservationView::getSeatNumber)
                                        .containsExactlyElementsOf(expectedSeatNumbers);

                        assertThat(result.get(0).getSeatNumber()).isNotNull();
                        assertThat(result.get(0).getReservation()).isNull();
                }
        }

        @Nested
        class FindByReservationDateTest {
                @Test
                void 予約が存在すれば結果を返す() {
                        LocalDate reservationDate = LocalDate.of(2026, 10, 1);
                        short seatNumber = 1;
                        String userId = "demo-user-1";
                        Reservation reservation = new Reservation(reservationDate, seatNumber, userId);
                        reservationRepository.save(reservation);

                        List<Reservation> result = reservationRepository
                                        .findByReservationDate(reservationDate);

                        assertThat(result).hasSize(1);
                        assertThat(result.get(0).getId()).isEqualTo(reservation.getId());
                        assertThat(result.get(0).getReservationDate())
                                        .isEqualTo(reservation.getReservationDate());
                        assertThat(result.get(0).getSeatNumber())
                                        .isEqualTo(reservation.getSeatNumber());
                        assertThat(result.get(0).getUserId()).isEqualTo(reservation.getUserId());
                        assertThat(result.get(0).getCreatedAt())
                                        .isEqualTo(reservation.getCreatedAt());
                }

                @Test
                void 予約が存在しなければ空の結果を返す() {
                        List<Reservation> result = reservationRepository
                                        .findByReservationDate(LocalDate.of(2099, 1, 1));

                        assertThat(result).isEmpty();
                }
        }
}
