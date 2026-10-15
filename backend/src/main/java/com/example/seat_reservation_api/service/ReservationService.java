package com.example.seat_reservation_api.service;

import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.exception.InvalidSeatException;
import com.example.seat_reservation_api.exception.ReservationNotFoundException;
import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.repository.SeatRepository;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.seat_reservation_api.repository.SeatReservationView;

@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;

    public ReservationService(ReservationRepository reservationRepository,
            SeatRepository seatRepository) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
    }

    public List<SeatReservationView> getSeatsWithReservation(LocalDate date) {
        return reservationRepository.findSeatsWithReservation(date);
    }

    public Reservation getReservationById(UUID id) {
        return reservationRepository.findById(id).orElse(null);
    }

    @Transactional
    public UUID reserve(LocalDate date, String userId, short seatNumber) {
        if (!seatRepository.existsById(seatNumber)) {
            throw new InvalidSeatException();
        }
        Reservation reservation = new Reservation(date, seatNumber, userId);
        return reservationRepository.save(reservation).getId();
    }

    @Transactional
    public void cancelReservation(UUID reservationId) {
        int deleted = reservationRepository.deleteReservationById(reservationId);
        if (deleted == 0) {
            throw new ReservationNotFoundException();
        }
    }
}
