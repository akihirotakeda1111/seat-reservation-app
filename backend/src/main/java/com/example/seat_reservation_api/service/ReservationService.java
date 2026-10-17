package com.example.seat_reservation_api.service;

import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.exception.InvalidSeatException;
import com.example.seat_reservation_api.exception.ReservationNotFoundException;
import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.repository.SeatRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.seat_reservation_api.repository.SeatReservationView;
import com.example.seat_reservation_api.validation.Validation;

@Service
public class ReservationService {

    private final Clock clock;

    private final ReservationRepository reservationRepository;
    private final SeatRepository seatRepository;

    public ReservationService(ReservationRepository reservationRepository,
            SeatRepository seatRepository, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.seatRepository = seatRepository;
        this.clock = clock;
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
    public void cancelOwnReservation(UUID reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(ReservationNotFoundException::new);

        if (!reservation.getUserId().equals(userId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "指定された予約はキャンセルできません。");
        }

        cancelReservation(reservation);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void cancelReservationAsAdmin(UUID reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(ReservationNotFoundException::new);

        cancelReservation(reservation);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<Reservation> getAllReservations(LocalDate date) {
        return reservationRepository.findByReservationDate(date);
    }

    private void cancelReservation(Reservation reservation) {

        if (Validation.isInvalidDate(reservation.getReservationDate(), clock)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "過去の予約はキャンセルできません。");
        }

        int deleted = reservationRepository.deleteReservationById(reservation.getId());
        if (deleted == 0) {
            throw new ReservationNotFoundException();
        }
    }
}
