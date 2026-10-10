package com.example.seat_reservation_api.entity;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

public class reservationTest {

    @Test
    public void reservationCreation_予約が作成される() {
        LocalDate reservationDate = LocalDate.of(2026, 10, 1);
        Short seatNumber = 1;
        String userId = "demo-user-1";
        Reservation reservation = new Reservation(reservationDate, seatNumber, userId);

        assert reservation.getId() == null;
        assert reservation.getReservationDate().equals(reservationDate);
        assert reservation.getSeatNumber().equals(seatNumber);
        assert reservation.getUserId().equals(userId);
        assert reservation.getCreatedAt() != null;
    }
}
