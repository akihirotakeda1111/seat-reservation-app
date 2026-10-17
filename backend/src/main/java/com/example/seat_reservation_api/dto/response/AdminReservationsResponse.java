package com.example.seat_reservation_api.dto.response;

import java.time.LocalDate;

public record AdminReservationsResponse(
                LocalDate date,
                AdminReservationResponse[] reservations) {
}
