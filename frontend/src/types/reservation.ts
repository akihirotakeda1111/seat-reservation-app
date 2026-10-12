export type DemoUser = 'demo-user-1' | 'demo-user-2';

export type Seat =
  | {
      seatNumber: number;
      status: 'AVAILABLE' | 'OCCUPIED';
      reservationId?: never;
    }
  | { seatNumber: number; status: 'MINE'; reservationId: string };

export interface SeatsResponse {
  date: string;
  seats: Seat[];
}

export interface ReservationResponse {
  id: string;
  date: string;
  seatNumber: number;
}

export interface ResultNotice {
  kind: 'success' | 'error' | 'info';
  text: string;
}
