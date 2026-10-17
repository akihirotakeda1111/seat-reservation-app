package com.example.seat_reservation_api.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record ReservationsRequest(@NotNull LocalDate date) {
}
