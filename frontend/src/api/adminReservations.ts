import { request } from './client';
import type { AdminReservationsResponse } from '../types/adminReservation';
export const getAdminReservations = (
  date: string,
  token: string,
  signal?: AbortSignal,
) =>
  request<AdminReservationsResponse>(
    `/admin/reservations?date=${encodeURIComponent(date)}`,
    token,
    { signal },
  );
export const cancelAdminReservation = (id: string, token: string) =>
  request<void>(`/admin/reservations/${encodeURIComponent(id)}`, token, {
    method: 'DELETE',
  });
