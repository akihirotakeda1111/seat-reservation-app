package com.example.seat_reservation_api.validation;

import java.time.Clock;
import java.time.LocalDate;

public class Validation {

    public static boolean isInvalidDemoUserId(String userId) {
        return !"demo-user-1".equals(userId) && !"demo-user-2".equals(userId);
    }

    public static boolean isInvalidDate(LocalDate date, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        return date == null || date.isBefore(today);
    }

    public static boolean isInvalidSeatNumber(Short seatId) {
        return seatId == null || seatId < 1;
    }
}
