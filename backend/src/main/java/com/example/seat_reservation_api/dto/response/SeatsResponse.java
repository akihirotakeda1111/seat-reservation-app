package com.example.seat_reservation_api.dto.response;

import java.time.LocalDate;

public record SeatsResponse(LocalDate date, SeatResponse[] seats) {
}
