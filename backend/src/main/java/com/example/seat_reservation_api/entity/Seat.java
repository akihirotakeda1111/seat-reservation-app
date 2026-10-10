package com.example.seat_reservation_api.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    private Short seatNumber;

    protected Seat() {}

    public Seat(Short seatNumber) {
        this.seatNumber = seatNumber;
    }

    public Short getSeatNumber() {
        return seatNumber;
    }
}
