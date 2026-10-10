package com.example.seat_reservation_api.dto.response;

import java.time.LocalDate;
import java.util.UUID;

public record ReservationsResponse(UUID id, LocalDate date, Short seatNumber) {
}
