package com.example.seat_reservation_api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreateReservationRequest(@NotNull @Min(1) Short seatNumber,
        @NotNull LocalDate date) {
}
