package com.example.seat_reservation_api.exception;

public class ReservationNotFoundException extends RuntimeException {
    public ReservationNotFoundException() {
        super("指定された予約が見つかりません。");
    }
}
