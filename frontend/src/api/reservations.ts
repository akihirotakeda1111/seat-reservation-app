import type { ReservationResponse, SeatsResponse } from '../types/reservation';

import { request } from './client';
export { ApiError } from './client';

export const getSeats = (
  date: string,
  accessToken: string,
  signal?: AbortSignal,
) =>
  request<SeatsResponse>(
    `/seats?date=${encodeURIComponent(date)}`,
    accessToken,
    {
      signal,
    },
  );

export const reserveSeat = (
  date: string,
  seatNumber: number,
  accessToken: string,
) =>
  request<ReservationResponse>('/reservations', accessToken, {
    method: 'POST',
    body: JSON.stringify({ date, seatNumber }),
  });

export const cancelReservation = (id: string, accessToken: string) =>
  request<void>(`/reservations/${encodeURIComponent(id)}`, accessToken, {
    method: 'DELETE',
  });
