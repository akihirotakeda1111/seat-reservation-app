package com.example.seat_reservation_api.dto.response;

import java.util.UUID;

import com.example.seat_reservation_api.entity.Reservation;
import com.fasterxml.jackson.annotation.JsonInclude;

public record SeatResponse(Short seatNumber, String status,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID reservationId) {
    public static SeatResponse from(Short seatNumber, Reservation reservation,
            String currentUserId) {
        if (reservation == null) {
            return new SeatResponse(seatNumber, "AVAILABLE", null);
        }

        if (reservation.getUserId().equals(currentUserId)) {
            return new SeatResponse(seatNumber, "MINE", reservation.getId());
        }

        return new SeatResponse(seatNumber, "OCCUPIED", null);
    }
}
