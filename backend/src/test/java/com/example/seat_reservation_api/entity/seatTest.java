package com.example.seat_reservation_api.entity;

import org.junit.jupiter.api.Test;

public class seatTest {

    @Test
    public void seatCreation_座席が作成される() {
        Short seatNumber = 1;
        Seat seat = new Seat(seatNumber);
        assert seat.getSeatNumber().equals(seatNumber);
    }
}
