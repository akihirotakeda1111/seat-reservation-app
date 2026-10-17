package com.example.seat_reservation_api.dto.response;

import java.util.UUID;

public record AdminReservationResponse(
        UUID id,
        short seatNumber,
        String userId) {
}
