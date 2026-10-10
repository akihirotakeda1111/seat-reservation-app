package com.example.seat_reservation_api.repository;

import com.example.seat_reservation_api.entity.Reservation;

public interface SeatReservationView {
    Short getSeatNumber();

    Reservation getReservation();
}
