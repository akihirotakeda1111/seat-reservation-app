import { vi } from 'vitest';
import type { Seat } from '../types/reservation';

export const D = '2026-10-12';
export const NEXT = '2026-10-13';
export const PREV = '2026-10-11';
export const A = 'demo-user-1';
export const B = 'demo-user-2';
export const initialSeats = (count = 30): Seat[] =>
  Array.from({ length: count }, (_, i) =>
    i === 4
      ? { seatNumber: 5, status: 'MINE', reservationId: 'mine-5' }
      : { seatNumber: i + 1, status: i === 7 ? 'OCCUPIED' : 'AVAILABLE' },
  );

export function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
export const problem = (status: number, detail: string) =>
  json({ status, detail }, status);
export const seatResponse = (seats = initialSeats(), date = D) =>
  json({ date, seats });

type Handler = (url: URL, init: RequestInit) => Promise<Response> | Response;
export function apiHarness() {
  const state: { seats: Seat[]; read?: Handler; write?: Handler } = {
    seats: initialSeats(),
  };
  const calls: { method: string; url: URL; init: RequestInit }[] = [];
  const fetch = vi.fn((path: string, init: RequestInit = {}) => {
    const url = new URL(path, 'http://localhost');
    const method = init.method ?? 'GET';
    calls.push({ method, url, init });
    const execute = async () => {
      if (method === 'GET')
        return state.read
          ? state.read(url, init)
          : seatResponse(state.seats, url.searchParams.get('date')!);
      if (state.write) return state.write(url, init);
      if (method === 'POST') {
        const { date, seatNumber } = JSON.parse(String(init.body));
        state.seats = state.seats.map((s) =>
          s.seatNumber === seatNumber
            ? { ...s, status: 'MINE', reservationId: 'created-id' }
            : s,
        );
        return json({ id: 'created-id', date, seatNumber }, 201);
      }
      state.seats = state.seats.map((s) =>
        s.status === 'MINE' && url.pathname.endsWith(s.reservationId)
          ? { seatNumber: s.seatNumber, status: 'AVAILABLE' }
          : s,
      );
      return new Response(null, { status: 204 });
    };
    return new Promise<Response>((resolve, reject) => {
      const abort = () => reject(new DOMException('Aborted', 'AbortError'));
      if (init.signal?.aborted) {
        abort();
        return;
      }
      init.signal?.addEventListener('abort', abort, { once: true });
      execute()
        .then(resolve, reject)
        .finally(() => init.signal?.removeEventListener('abort', abort));
    });
  });
  vi.stubGlobal('fetch', fetch);
  return {
    state,
    calls,
    fetch,
    reads: () => calls.filter((c) => c.method === 'GET'),
    writes: () => calls.filter((c) => c.method !== 'GET'),
  };
}
