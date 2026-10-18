import { StrictMode } from 'react';
import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  cancelReservation,
  getSeats,
  reserveSeat,
} from '../api/reservations';
import type { Seat, SeatsResponse } from '../types/reservation';
import { useSeatReservation } from './useSeatReservation';
import { initialSeats } from '../test/apiHarness';

vi.mock('../api/reservations', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/reservations')>();
  return {
    ...actual,
    getSeats: vi.fn(),
    reserveSeat: vi.fn(),
    cancelReservation: vi.fn(),
  };
});

const onUnauthorized = vi.fn();
const available: Seat = { seatNumber: 1, status: 'AVAILABLE' };
const mine: Seat = { seatNumber: 5, status: 'MINE', reservationId: 'mine-5' };
const response = (
  seats: Seat[] = initialSeats(),
  date = '2026-10-12',
): SeatsResponse => ({ date, seats });
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
async function settle() {
  await act(async () => {});
}

beforeEach(() => {
  onUnauthorized.mockClear();
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-12T01:00:00Z'));
  vi.mocked(getSeats)
    .mockReset()
    .mockImplementation(async (date) => response(undefined, date));
  vi.mocked(reserveSeat).mockReset();
  vi.mocked(cancelReservation).mockReset();
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('座席画面の状態管理', () => {
  it('[POLL-03][POLL-04] 他の座席だけの更新では選択を維持し、選択席の状態変更では解除する', async () => {
    const { result } = renderHook(
      () => useSeatReservation('token-a', onUnauthorized),
      { wrapper: StrictMode },
    );
    await settle();
    act(() => result.current.selectSeat(available));
    vi.mocked(getSeats).mockResolvedValue(
      response(
        initialSeats().map((s) =>
          s.seatNumber === 5 ? { seatNumber: 5, status: 'AVAILABLE' } : s,
        ),
      ),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(result.current.selectedSeat?.seatNumber).toBe(1);
    expect(result.current.seats[4].status).toBe('AVAILABLE');
    vi.mocked(getSeats).mockResolvedValue(
      response(
        initialSeats().map((s) =>
          s.seatNumber === 1 ? { seatNumber: 1, status: 'OCCUPIED' } : s,
        ),
      ),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(result.current.selectedSeat).toBeNull();
    expect(result.current.notice?.text).toContain('座席情報が更新されました');
    await act(async () => {
      await result.current.reserve();
    });
    expect(reserveSeat).not.toHaveBeenCalled();
  });

  it('[POLL-05] 同じMINEでも予約IDが変わったら選択解除する', async () => {
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(mine));
    vi.mocked(getSeats).mockResolvedValue(
      response(
        initialSeats().map((s) =>
          s.seatNumber === 5 ? { ...mine, reservationId: 'new-id' } : s,
        ),
      ),
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(result.current.selectedSeat).toBeNull();
    await act(async () => {
      await result.current.cancel();
    });
    expect(cancelReservation).not.toHaveBeenCalled();
  });

  it.each(['2026-10-13', '2026-10-14'])(
    '[CTX-01] %sへの日付変更後は最新の座席を保持し、古い応答を破棄する',
    async (date) => {
      const slow = deferred<SeatsResponse>();
      const latestSeats: Seat[] = [
        { seatNumber: 1, status: 'MINE', reservationId: 'latest-user-2' },
        { seatNumber: 5, status: 'OCCUPIED' },
      ];
      vi.mocked(getSeats)
        .mockImplementation(async (requestedDate) =>
          response(latestSeats, requestedDate),
        )
        .mockReturnValueOnce(slow.promise);
      const { result } = renderHook(() =>
        useSeatReservation('token-a', onUnauthorized),
      );
      if (date !== '2026-10-12') {
        act(() => result.current.changeDate(date));
        await settle();
      }

      await settle();
      expect(result.current.seats).toEqual(latestSeats);
      await act(async () => {
        slow.resolve(response([{ seatNumber: 99, status: 'AVAILABLE' }]));
      });
      expect(result.current.date).toBe(date);

      expect(result.current.seats).toEqual(latestSeats);
      expect(result.current.seats.some((s) => s.seatNumber === 99)).toBe(false);
    },
  );

  it('二重送信を抑止し、成功後は再取得・選択解除する', async () => {
    const pending = deferred<{
      id: string;
      date: string;
      seatNumber: number;
    }>();
    vi.mocked(reserveSeat).mockReturnValue(pending.promise);
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(available));
    act(() => {
      void result.current.reserve();
      void result.current.reserve();
    });
    expect(reserveSeat).toHaveBeenCalledTimes(1);
    expect(result.current.submitting).toBe(true);
    act(() => result.current.changeDate('2026-10-13'));
    expect(result.current.date).toBe('2026-10-12');
    vi.mocked(getSeats).mockResolvedValue(
      response([{ seatNumber: 1, status: 'MINE', reservationId: 'new' }]),
    );
    await act(async () => {
      pending.resolve({ id: 'new', date: '2026-10-12', seatNumber: 1 });
    });
    await settle();
    expect(result.current.selectedSeat).toBeNull();
    expect(result.current.notice?.kind).toBe('success');
    expect(result.current.seats[0].status).toBe('MINE');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(result.current.notice?.kind).toBe('success');
  });

  it('キャンセルに選択席の予約IDを使う', async () => {
    vi.mocked(cancelReservation).mockResolvedValue();
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(mine));
    await act(async () => {
      await result.current.cancel();
    });
    expect(cancelReservation).toHaveBeenCalledWith('mine-5', 'token-a');
    expect(result.current.selectedSeat).toBeNull();
  });

  it('409を通知して再取得し、結果不明時には書き込みを再送しない', async () => {
    vi.mocked(reserveSeat)
      .mockRejectedValueOnce(new ApiError(409, '競合'))
      .mockRejectedValueOnce(new TypeError('network'));
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(available));
    await act(async () => {
      await result.current.reserve();
    });
    expect(result.current.notice?.text).toContain('先に予約');
    act(() => result.current.selectSeat(available));
    await act(async () => {
      await result.current.reserve();
    });
    expect(result.current.notice?.text).toContain('結果を確認できません');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15000);
    });
    expect(reserveSeat).toHaveBeenCalledTimes(2);
  });

  it('更新失敗で操作を止め、再取得成功で復帰する', async () => {
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(available));
    vi.mocked(getSeats).mockRejectedValueOnce(new Error('offline'));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(result.current.fetchError).toBe(true);
    expect(result.current.canSelect).toBe(false);
    expect(result.current.selectedSeat).toBeNull();
    expect(result.current.seats).toHaveLength(30);
    act(() => result.current.retry());
    await settle();
    expect(result.current.canSelect).toBe(true);
  });

  it('[DATE-07] 日本時間の日付が変わると前日になった選択を解除する', async () => {
    vi.setSystemTime(new Date('2026-10-12T14:59:59Z'));
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(available));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(result.current.past).toBe(true);
    expect(result.current.selectedSeat).toBeNull();
    expect(result.current.canSelect).toBe(false);
  });

  it('[POLL-08] 非表示中は更新を休止し、再表示で即時取得する', async () => {
    const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
    renderHook(() => useSeatReservation('token-a', onUnauthorized));
    await settle();
    const count = vi.mocked(getSeats).mock.calls.length;
    hidden.mockReturnValue(true);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15000);
    });
    expect(getSeats).toHaveBeenCalledTimes(count);
    hidden.mockReturnValue(false);
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    await settle();
    expect(getSeats).toHaveBeenCalledTimes(count + 1);
  });

  it('[POLL-01] 前回完了4999msでは取得せず5000msで現在の条件を取得', async () => {
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.changeDate('2026-10-13'));
    await settle();
    act(() => result.current.changeDate('2026-10-13'));
    await settle();
    const count = vi.mocked(getSeats).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4999);
    });
    expect(getSeats).toHaveBeenCalledTimes(count);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(getSeats).toHaveBeenCalledTimes(count + 1);
    expect(getSeats).toHaveBeenLastCalledWith(
      '2026-10-13',
      'token-a',
      expect.any(AbortSignal),
    );
  });

  it('[POLL-02] 内容不変なら座席配列と選択を維持し最終確認を更新', async () => {
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(available));
    const seats = result.current.seats,
      time = result.current.lastChecked!.getTime();
    vi.mocked(getSeats).mockResolvedValue(response(initialSeats()));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(result.current.seats).toBe(seats);
    expect(result.current.selectedSeat?.seatNumber).toBe(1);
    expect(result.current.lastChecked!.getTime()).toBe(time + 5000);
  });

  it('[POLL-06] 選択席が応答から消えたら通知・選択解除', async () => {
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(available));
    const remaining = initialSeats().filter((s) => s.seatNumber !== 1);
    vi.mocked(getSeats).mockResolvedValue(response(remaining));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(result.current.seats).toEqual(remaining);
    expect(result.current.selectedSeat).toBeNull();
    expect(result.current.notice?.text).toContain('座席情報が更新されました');
  });

  it('[POLL-07] 6秒のGET中に重複せず完了から5秒で再取得', async () => {
    const slow = deferred<SeatsResponse>();
    vi.mocked(getSeats).mockReturnValueOnce(slow.promise);
    renderHook(() => useSeatReservation('token-a', onUnauthorized));
    await settle();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(getSeats).toHaveBeenCalledTimes(1);
    await act(async () => {
      slow.resolve(response());
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4999);
    });
    expect(getSeats).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(getSeats).toHaveBeenCalledTimes(2);
  });

  it('[POLL-09] 予約前の遅いGETは予約後の表示を上書きしない', async () => {
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    const slow = deferred<SeatsResponse>();
    vi.mocked(getSeats).mockReturnValueOnce(slow.promise);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    const oldSignal = vi.mocked(getSeats).mock.calls.at(-1)![2]!;
    act(() => result.current.selectSeat(available));
    const booked: Seat = {
      seatNumber: 1,
      status: 'MINE',
      reservationId: 'created',
    };
    vi.mocked(reserveSeat).mockResolvedValue({
      id: 'created',
      date: '2026-10-12',
      seatNumber: 1,
    });
    vi.mocked(getSeats).mockResolvedValue(response([booked]));
    await act(async () => {
      await result.current.reserve();
    });
    expect(oldSignal.aborted).toBe(true);
    expect(result.current.seats).toEqual([booked]);
    await act(async () => {
      slow.resolve(response());
    });
    expect(result.current.seats).toEqual([booked]);
    expect(result.current.notice?.kind).toBe('success');
  });

  it.each(['GET待機中', 'タイマー待機中'])(
    '[POLL-10] %sの破棄でタイマー・イベント・通信を終了',
    async (mode) => {
      const slow = deferred<SeatsResponse>();
      if (mode === 'GET待機中')
        vi.mocked(getSeats).mockReturnValueOnce(slow.promise);
      const remove = vi.spyOn(document, 'removeEventListener');
      const { result, unmount } = renderHook(() =>
        useSeatReservation('token-a', onUnauthorized),
      );
      await settle();
      const snapshot = result.current,
        count = vi.mocked(getSeats).mock.calls.length;
      const signal = vi.mocked(getSeats).mock.calls.at(-1)![2]!;
      unmount();
      expect(signal.aborted).toBe(true);
      expect(vi.getTimerCount()).toBe(0);
      expect(remove).toHaveBeenCalledWith(
        'visibilitychange',
        expect.any(Function),
      );
      await act(async () => {
        slow.resolve(response());
        document.dispatchEvent(new Event('visibilitychange'));
        await vi.advanceTimersByTimeAsync(15000);
      });
      expect(getSeats).toHaveBeenCalledTimes(count);
      expect(result.current).toBe(snapshot);
    },
  );
});

it('GETの401でポーリングを停止し再ログインを要求する', async () => {
  vi.mocked(getSeats).mockRejectedValue(new ApiError(401, '認証が必要です。'));
  const { result } = renderHook(() =>
    useSeatReservation('token-a', onUnauthorized),
  );
  await settle();
  expect(onUnauthorized).toHaveBeenCalledOnce();
  expect(result.current.canSelect).toBe(false);
  const count = vi.mocked(getSeats).mock.calls.length;
  await act(async () => {
    await vi.advanceTimersByTimeAsync(15000);
  });
  expect(getSeats).toHaveBeenCalledTimes(count);
});

it.each([
  { method: 'POST', status: 401 },
  { method: 'POST', status: 403 },
  { method: 'DELETE', status: 401 },
  { method: 'DELETE', status: 403 },
])(
  '[AUTH-API-02][AUTH-API-03] $method の $status を区別し書き込みは再送しない',
  async ({ method, status }) => {
    const action = method === 'POST' ? reserveSeat : cancelReservation;
    vi.mocked(action).mockRejectedValue(
      new ApiError(status, '拒否されました。'),
    );
    const { result } = renderHook(() =>
      useSeatReservation('token-a', onUnauthorized),
    );
    await settle();
    act(() => result.current.selectSeat(method === 'POST' ? available : mine));
    await act(async () => {
      if (method === 'POST') await result.current.reserve();
      else await result.current.cancel();
    });
    const count = vi.mocked(getSeats).mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(15000);
    });
    expect(action).toHaveBeenCalledOnce();
    expect(
      method === 'POST' ? cancelReservation : reserveSeat,
    ).not.toHaveBeenCalled();
    if (status === 401) {
      expect(onUnauthorized).toHaveBeenCalledOnce();
      expect(getSeats).toHaveBeenCalledTimes(count);
    } else {
      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(result.current.notice?.text).toBe('拒否されました。');
      expect(vi.mocked(getSeats).mock.calls.length).toBeGreaterThan(count);
    }
  },
);

it('[AUTH-API-04] GETの403は再認証せず取得失敗を表示し再取得できる', async () => {
  vi.mocked(getSeats).mockRejectedValueOnce(
    new ApiError(403, '権限がありません。'),
  );
  const { result } = renderHook(() =>
    useSeatReservation('token-a', onUnauthorized),
  );
  await settle();
  expect(onUnauthorized).not.toHaveBeenCalled();
  expect(result.current.fetchError).toBe(true);
  expect(result.current.canSelect).toBe(false);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(5000);
  });
  expect(getSeats).toHaveBeenCalledTimes(2);
  expect(result.current.fetchError).toBe(false);
  expect(result.current.canSelect).toBe(true);
  expect(onUnauthorized).not.toHaveBeenCalled();
});
