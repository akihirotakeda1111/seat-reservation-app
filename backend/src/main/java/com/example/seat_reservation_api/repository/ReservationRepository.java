package com.example.seat_reservation_api.repository;

import com.example.seat_reservation_api.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    @Query("""
                SELECT s.seatNumber AS seatNumber, r AS reservation
                FROM Seat s
                LEFT JOIN Reservation r
                    ON r.seatNumber = s.seatNumber
                    AND r.reservationDate = :date
                ORDER BY s.seatNumber
            """)
    List<SeatReservationView> findSeatsWithReservation(@Param("date") LocalDate date);

    List<Reservation> findByReservationDate(LocalDate reservationDate);
}
