package com.example.seat_reservation_api.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record SeatsRequest(@NotNull LocalDate date) {
}
