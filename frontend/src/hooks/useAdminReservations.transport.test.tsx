import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useAdminReservations } from './useAdminReservations';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
it.each(
  ['GET', 'DELETE'].flatMap((method) =>
    ['切断', 'タイムアウト'].map((failure) => ({ method, failure })),
  ),
)(
  '[ADMIN-10] $method の$failureを区別し書き込みを再送しない',
  async ({ method, failure }) => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-18T01:00:00Z'));
    let fail = false;
    let aborted = false;
    const fetch = vi.fn((_url: string, init: RequestInit) => {
      if (fail && (init.method ?? 'GET') === method) {
        if (failure === '切断')
          return Promise.reject(new TypeError('Network error'));
        return new Promise<Response>((_resolve, reject) => {
          init.signal!.addEventListener('abort', () => {
            aborted = true;
            reject(new DOMException('Aborted', 'AbortError'));
          });
        });
      }
      return Promise.resolve(
        new Response(
          JSON.stringify({
            date: '2026-10-18',
            reservations: [{ id: 'r1', seatNumber: 5, userId: 'other' }],
          }),
        ),
      );
    });
    vi.stubGlobal('fetch', fetch);
    const unauthorized = vi.fn();
    const { result } = renderHook(() =>
      useAdminReservations('token', unauthorized),
    );
    await act(async () => {
      await result.current.fetchReservations();
    });
    fail = true;
    let pending!: Promise<void>;
    if (method === 'DELETE')
      act(() =>
        result.current.openCancelDialog(result.current.reservations[0]),
      );
    await act(async () => {
      pending =
        method === 'GET'
          ? result.current.fetchReservations()
          : result.current.confirmCancellation();
      if (failure === 'タイムアウト') {
        await vi.advanceTimersByTimeAsync(9999);
        expect(aborted).toBe(false);
        await vi.advanceTimersByTimeAsync(1);
        expect(aborted).toBe(true);
      }
      await pending;
    });
    expect(unauthorized).not.toHaveBeenCalled();
    if (method === 'GET') {
      expect(result.current.fetchError).toBe(true);
      act(() =>
        result.current.openCancelDialog(result.current.reservations[0]),
      );
      expect(result.current.cancelTarget).toBeNull();
    } else {
      expect(result.current.notice?.text).toContain('結果を確認できません');
      expect(result.current.notice?.kind).toBe('error');
      expect(result.current.cancelTarget).toBeNull();
    }
    const count = fetch.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15000);
    });
    expect(fetch).toHaveBeenCalledTimes(count);
    expect(
      fetch.mock.calls.filter(([, init]) => init.method === 'DELETE'),
    ).toHaveLength(method === 'DELETE' ? 1 : 0);
  },
);
