import type {
  DemoUser,
  ReservationResponse,
  SeatsResponse,
} from '../types/reservation';

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
  user: DemoUser,
  init: RequestInit = {},
): Promise<T> {
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
        'X-Demo-User': user,
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

export const getSeats = (date: string, user: DemoUser, signal?: AbortSignal) =>
  request<SeatsResponse>(`/seats?date=${encodeURIComponent(date)}`, user, {
    signal,
  });

export const reserveSeat = (date: string, seatNumber: number, user: DemoUser) =>
  request<ReservationResponse>('/reservations', user, {
    method: 'POST',
    body: JSON.stringify({ date, seatNumber }),
  });

export const cancelReservation = (id: string, user: DemoUser) =>
  request<void>(`/reservations/${encodeURIComponent(id)}`, user, {
    method: 'DELETE',
  });
