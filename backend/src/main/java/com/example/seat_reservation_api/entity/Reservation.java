package com.example.seat_reservation_api.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reservation_date", nullable = false)
    private LocalDate reservationDate;

    @Column(name = "seat_number", nullable = false)
    private Short seatNumber;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Reservation() {}

    public Reservation(LocalDate reservationDate, Short seatNumber, String userId) {
        this.reservationDate = reservationDate;
        this.seatNumber = seatNumber;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public LocalDate getReservationDate() {
        return reservationDate;
    }

    public Short getSeatNumber() {
        return seatNumber;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
