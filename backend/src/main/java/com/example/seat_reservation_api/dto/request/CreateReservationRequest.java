package com.example.seat_reservation_api.dto.request;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateReservationRequest(@NotNull Short seatNumber,
        @NotNull @FutureOrPresent LocalDate date) {
}
