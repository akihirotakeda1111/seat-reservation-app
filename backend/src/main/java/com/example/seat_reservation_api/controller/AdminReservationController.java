package com.example.seat_reservation_api.controller;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.ResponseEntity;

import com.example.seat_reservation_api.service.ReservationService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import com.example.seat_reservation_api.dto.request.SeatsRequest;
import com.example.seat_reservation_api.dto.response.AdminReservationResponse;
import com.example.seat_reservation_api.dto.response.AdminReservationsResponse;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminReservationController {

    private final ReservationService reservationService;

    public AdminReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/reservations")
    public ResponseEntity<AdminReservationsResponse> getReservations(@Valid @ModelAttribute SeatsRequest request) {

        LocalDate date = request.date();

        AdminReservationResponse[] reservation = reservationService.getAllReservations(date).stream()
                .map(row -> new AdminReservationResponse(row.getId(), row.getSeatNumber(), row.getUserId()))
                .toArray(AdminReservationResponse[]::new);
        AdminReservationsResponse reservationsResponse = new AdminReservationsResponse(date, reservation);

        return ResponseEntity.ok(reservationsResponse);
    }

    @DeleteMapping("/reservations/{id}")
    public ResponseEntity<String> deleteReservation(@PathVariable @NotNull UUID id) {

        reservationService.cancelReservationAsAdmin(id);

        return ResponseEntity.noContent().build();
    }
}
