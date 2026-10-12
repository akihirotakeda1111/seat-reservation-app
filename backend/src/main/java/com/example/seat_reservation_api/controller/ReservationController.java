package com.example.seat_reservation_api.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;

import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.service.ReservationService;
import com.example.seat_reservation_api.validation.Validation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.example.seat_reservation_api.component.CurrentUserProvider;
import com.example.seat_reservation_api.dto.request.CreateReservationRequest;
import com.example.seat_reservation_api.dto.request.SeatsRequest;
import com.example.seat_reservation_api.dto.response.ReservationsResponse;
import com.example.seat_reservation_api.dto.response.SeatResponse;
import com.example.seat_reservation_api.dto.response.SeatsResponse;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import java.net.URI;

@RestController
@RequestMapping("/api")
public class ReservationController {

    private final Clock clock;

    private final ReservationService reservationService;
    private final CurrentUserProvider currentUserProvider;

    public ReservationController(ReservationService reservationService,
            CurrentUserProvider currentUserProvider, Clock clock) {
        this.reservationService = reservationService;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @GetMapping("/seats")
    public ResponseEntity<SeatsResponse> getSeatsByDate(
            @Valid @ModelAttribute SeatsRequest request) {

        LocalDate date = request.date();
        String userId = currentUserProvider.getUserId();
        SeatResponse[] seats = reservationService.getSeatsWithReservation(date).stream()
                .map(row -> SeatResponse.from(row.getSeatNumber(), row.getReservation(), userId))
                .toArray(SeatResponse[]::new);
        SeatsResponse seatsResponse = new SeatsResponse(date, seats);

        return ResponseEntity.ok(seatsResponse);
    }

    @PostMapping("/reservations")
    public ResponseEntity<ReservationsResponse> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {

        String userId = currentUserProvider.getUserId();
        LocalDate date = request.date();
        short seatNumber = request.seatNumber();

        try {
            if (Validation.isInvalidDate(date, clock)) {
                throw new Exception();
            }
            if (Validation.isInvalidSeatNumber(seatNumber)) {
                throw new Exception();
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "リクエストの内容が不正です。", e);
        }

        UUID reservationId = reservationService.reserve(date, userId, seatNumber);

        Reservation reservation = reservationService.getReservationById(reservationId);
        ReservationsResponse reservationsResponse = new ReservationsResponse(reservation.getId(),
                reservation.getReservationDate(), reservation.getSeatNumber());

        return ResponseEntity.created(URI.create("/api/reservations/" + reservationsResponse.id()))
                .body(reservationsResponse);
    }

    @DeleteMapping("/reservations/{id}")
    public ResponseEntity<String> deleteReservation(@PathVariable @NotNull UUID id) {

        String userId = currentUserProvider.getUserId();
        Reservation reservation = reservationService.getReservationById(id);
        if (reservation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "指定された予約が見つかりません。");
        }
        if (!reservation.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "指定された予約はキャンセルできません。");
        }
        if (Validation.isInvalidDate(reservation.getReservationDate(), clock)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "過去の予約はキャンセルできません。");
        }

        reservationService.cancelReservation(reservation.getId());

        return ResponseEntity.noContent().build();
    }
}
