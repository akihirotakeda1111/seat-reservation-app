export interface AdminReservation {
  id: string;
  seatNumber: number;
  userId: string;
}
export interface AdminReservationsResponse {
  date: string;
  reservations: AdminReservation[];
}
