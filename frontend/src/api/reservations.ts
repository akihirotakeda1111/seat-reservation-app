import type { ReservationResponse, SeatsResponse } from '../types/reservation';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    detail: string,
  ) {
    super(detail);
    this.name = 'ApiError';
  }
}

async function request<T>(
  path: string,
  accessToken: string,
  init: RequestInit = {},
): Promise<T> {
  if (!accessToken) throw new ApiError(401, '認証が必要です。');
  const controller = new AbortController();
  const abort = () => controller.abort();
  const timeout = setTimeout(abort, 10_000);
  init.signal?.addEventListener('abort', abort, { once: true });
  if (init.signal?.aborted) abort();
  try {
    const response = await fetch(`/api${path}`, {
      ...init,
      cache: 'no-store',
      signal: controller.signal,
      headers: {
        Accept: 'application/json, application/problem+json',
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        Authorization: `Bearer ${accessToken}`,
      },
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => null);
      throw new ApiError(
        response.status,
        typeof problem?.detail === 'string'
          ? problem.detail
          : 'リクエストを処理できませんでした。',
      );
    }
    if (response.status === 204) return undefined as T;
    return (await response.json()) as T;
  } finally {
    clearTimeout(timeout);
    init.signal?.removeEventListener('abort', abort);
  }
}

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
