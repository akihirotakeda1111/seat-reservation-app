CREATE TABLE seats (
    seat_number SMALLINT NOT NULL,
    CONSTRAINT pk_seats PRIMARY KEY (seat_number),
    CONSTRAINT ck_seats_seat_number_positive CHECK (seat_number > 0)
);

CREATE TABLE reservations (
    id UUID NOT NULL,
    reservation_date DATE NOT NULL,
    seat_number SMALLINT NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_reservations PRIMARY KEY (id),
    CONSTRAINT fk_reservations_seat
        FOREIGN KEY (seat_number) REFERENCES seats (seat_number)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT uq_reservations_date_seat
        UNIQUE (reservation_date, seat_number)
);

INSERT INTO seats (seat_number)
SELECT generate_series(1, 30);