import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  cancelReservation,
  getSeats,
  reserveSeat,
} from './reservations';

afterEach(() => vi.unstubAllGlobals());

describe('API契約', () => {
  it('[API-01] GETに日付と仮ユーザーヘッダーを付ける', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ date: '2026-10-12', seats: [] })),
      );
    vi.stubGlobal('fetch', fetch);
    await getSeats('2026-10-12', 'demo-user-2');
    expect(fetch).toHaveBeenCalledWith(
      '/api/seats?date=2026-10-12',
      expect.objectContaining({
        cache: 'no-store',
        headers: expect.objectContaining({ 'X-Demo-User': 'demo-user-2' }),
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
    await reserveSeat('2026-10-12', 1, 'demo-user-1');
    expect(fetch).toHaveBeenCalledWith(
      '/api/reservations',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'X-Demo-User': 'demo-user-1',
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
    await expect(
      cancelReservation('id', 'demo-user-1'),
    ).resolves.toBeUndefined();
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
    await expect(reserveSeat('2026-10-12', 1, 'demo-user-1')).rejects.toEqual(
      new ApiError(409, '予約済みです。'),
    );
  });
  it('[API-03] DELETEは予約IDとユーザーヘッダー、本文なし', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    await cancelReservation('reservation-id', 'demo-user-1');
    expect(fetch).toHaveBeenCalledWith(
      '/api/reservations/reservation-id',
      expect.objectContaining({
        method: 'DELETE',
        headers: expect.objectContaining({ 'X-Demo-User': 'demo-user-1' }),
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
    await expect(getSeats('2026-10-12', 'demo-user-1')).rejects.toEqual(
      new ApiError(502, 'リクエストを処理できませんでした。'),
    );
  });
});
