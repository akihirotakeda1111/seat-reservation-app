package com.example.seat_reservation_api.repository;

import com.example.seat_reservation_api.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Short> {
}
