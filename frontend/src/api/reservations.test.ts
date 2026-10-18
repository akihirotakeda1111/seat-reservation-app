import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  cancelReservation,
  getSeats,
  reserveSeat,
} from './reservations';

afterEach(() => vi.unstubAllGlobals());

describe('API契約', () => {
  it('[API-01] GETに日付とBearerヘッダーを付ける', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ date: '2026-10-12', seats: [] })),
      );
    vi.stubGlobal('fetch', fetch);
    await getSeats('2026-10-12', 'token-b');
    expect(fetch).toHaveBeenCalledWith(
      '/api/seats?date=2026-10-12',
      expect.objectContaining({
        cache: 'no-store',
        headers: expect.objectContaining({ Authorization: 'Bearer token-b' }),
      }),
    );
  });
  it('[API-02] POSTの本文は日付・座席番号のみ', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ id: 'id', date: '2026-10-12', seatNumber: 1 }),
          { status: 201 },
        ),
      );
    vi.stubGlobal('fetch', fetch);
    await reserveSeat('2026-10-12', 1, 'token-a');
    expect(fetch).toHaveBeenCalledWith(
      '/api/reservations',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          Authorization: 'Bearer token-a',
        }),
        body: JSON.stringify({ date: '2026-10-12', seatNumber: 1 }),
      }),
    );
  });
  it('[API-04] キャンセルの204は本文解析をしない', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    await expect(cancelReservation('id', 'token-a')).resolves.toBeUndefined();
  });
  it('[API-05] ProblemDetailのstatusとdetailを保持する', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ detail: '予約済みです。' }), {
          status: 409,
        }),
      ),
    );
    await expect(reserveSeat('2026-10-12', 1, 'token-a')).rejects.toEqual(
      new ApiError(409, '予約済みです。'),
    );
  });
  it('[API-03] DELETEは予約IDとBearerヘッダー、本文なし', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    await cancelReservation('reservation-id', 'token-a');
    expect(fetch).toHaveBeenCalledWith(
      '/api/reservations/reservation-id',
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({ Authorization: 'Bearer token-a' }),
      }),
    );
    expect(fetch.mock.calls[0][1].body).toBeUndefined();
  });
  it('[API-06] HTMLエラーでもHTTPステータスと代替メッセージを保持', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          new Response('<html>Bad gateway</html>', { status: 502 }),
        ),
    );
    await expect(getSeats('2026-10-12', 'token-a')).rejects.toEqual(
      new ApiError(502, 'リクエストを処理できませんでした。'),
    );
  });
});

it.each(['GET', 'POST', 'DELETE'] as const)(
  '[AUTH-API-05] %sは空トークンなら通信せず401を返す',
  async (method) => {
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    const request =
      method === 'GET'
        ? getSeats('2026-10-12', '')
        : method === 'POST'
          ? reserveSeat('2026-10-12', 1, '')
          : cancelReservation('id', '');
    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(fetch).not.toHaveBeenCalled();
  },
);
