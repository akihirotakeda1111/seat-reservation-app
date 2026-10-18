import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useAdminReservations } from './useAdminReservations';
import { ApiError } from '../api/client';
import {
  getAdminReservations,
  cancelAdminReservation,
} from '../api/adminReservations';
vi.mock('../api/adminReservations', () => ({
  getAdminReservations: vi.fn(),
  cancelAdminReservation: vi.fn(),
}));
const row = { id: 'reservation-1', seatNumber: 5, userId: 'other-user' };
afterEach(() => vi.resetAllMocks());

it('[ADMIN-01] 明示取得・他人の予約取消・再取得と二重送信抑止', async () => {
  const { result } = renderHook(() =>
    useAdminReservations('admin-token', vi.fn()),
  );
  const date = result.current.date;
  vi.mocked(getAdminReservations)
    .mockResolvedValueOnce({ date, reservations: [row] })
    .mockResolvedValue({ date, reservations: [] });
  expect(getAdminReservations).not.toHaveBeenCalled();
  await act(async () => {
    await result.current.fetchReservations();
  });
  act(() => result.current.openCancelDialog(row));
  let finish!: () => void;
  vi.mocked(cancelAdminReservation).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  let pending!: Promise<void>;
  act(() => {
    pending = result.current.confirmCancellation();
  });
  act(() => {
    void result.current.confirmCancellation();
    result.current.changeDate('2099-01-01');
  });
  expect(result.current.date).toBe(date);
  expect(cancelAdminReservation).toHaveBeenCalledExactlyOnceWith(
    row.id,
    'admin-token',
  );
  await act(async () => {
    finish();
    await pending;
  });
  expect(result.current.reservations).toEqual([]);
  expect(result.current.notice?.kind).toBe('success');
  expect(result.current.cancelTarget).toBeNull();
  expect(getAdminReservations).toHaveBeenCalledTimes(2);
});
it('[ADMIN-02] 日付変更で旧一覧と遅い応答を破棄し空日付では取得しない', async () => {
  let finish!: (value: { date: string; reservations: (typeof row)[] }) => void;
  vi.mocked(getAdminReservations).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const { result } = renderHook(() => useAdminReservations('token', vi.fn()));
  const old = result.current.date;
  let pending!: Promise<void>;
  act(() => {
    pending = result.current.fetchReservations();
  });
  act(() => result.current.changeDate('2099-01-01'));
  await act(async () => {
    finish({ date: old, reservations: [row] });
    await pending;
  });
  expect(result.current.reservations).toEqual([]);
  expect(result.current.loaded).toBe(false);
  act(() => result.current.changeDate(''));
  await act(async () => {
    await result.current.fetchReservations();
  });
  expect(getAdminReservations).toHaveBeenCalledTimes(1);
});
it.each([401, 403, 500])(
  '[ADMIN-03] GET %sの認証・権限・取得エラー',
  async (status) => {
    vi.mocked(getAdminReservations).mockRejectedValue(
      new ApiError(status, 'error'),
    );
    const unauthorized = vi.fn();
    const { result } = renderHook(() =>
      useAdminReservations('token', unauthorized),
    );
    await act(async () => {
      await result.current.fetchReservations();
    });
    expect(unauthorized).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
    expect(result.current.fetchError).toBe(status !== 401);
    if (status === 403)
      expect(result.current.notice?.text).toContain('管理者権限');
  },
);
it.each([401, 403, 404, 400, 500])(
  '[ADMIN-04] DELETE %sは再送せず401以外で一覧再取得',
  async (status) => {
    const unauthorized = vi.fn();
    const { result } = renderHook(() =>
      useAdminReservations('token', unauthorized),
    );
    vi.mocked(getAdminReservations).mockResolvedValue({
      date: result.current.date,
      reservations: [row],
    });
    vi.mocked(cancelAdminReservation).mockRejectedValue(
      new ApiError(status, '取消できません'),
    );
    await act(async () => {
      await result.current.fetchReservations();
    });
    act(() => result.current.openCancelDialog(row));
    await act(async () => {
      await result.current.confirmCancellation();
    });
    expect(cancelAdminReservation).toHaveBeenCalledOnce();
    expect(unauthorized).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
    expect(getAdminReservations).toHaveBeenCalledTimes(status === 401 ? 1 : 2);
    if (status !== 401) {
      expect(result.current.notice?.kind).toBe('error');
      expect(result.current.notice?.text).toBe(
        status === 500
          ? '操作の結果を確認できません。予約一覧を再取得して確認してください。'
          : '取消できません',
      );
    }
  },
);
it('[ADMIN-05] 過去日の予約は確認ダイアログを開かず取消しない', async () => {
  const { result } = renderHook(() => useAdminReservations('token', vi.fn()));
  act(() => result.current.changeDate('2000-01-01'));
  vi.mocked(getAdminReservations).mockResolvedValue({
    date: '2000-01-01',
    reservations: [row],
  });
  await act(async () => {
    await result.current.fetchReservations();
  });
  act(() => result.current.openCancelDialog(row));
  await waitFor(() => expect(result.current.cancelTarget).toBeNull());
  await act(async () => {
    await result.current.confirmCancellation();
  });
  expect(cancelAdminReservation).not.toHaveBeenCalled();
});

it.each(['timer', 'confirm'] as const)(
  '[ADMIN-06] 確認中の日付越えを%sで検出し取消しない',
  async (mode) => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-10-18T14:59:59Z'));
    try {
      const { result } = renderHook(() =>
        useAdminReservations('token', vi.fn()),
      );
      vi.mocked(getAdminReservations).mockResolvedValue({
        date: result.current.date,
        reservations: [row],
      });
      await act(async () => {
        await result.current.fetchReservations();
      });
      act(() => result.current.openCancelDialog(row));
      expect(result.current.cancelTarget).toEqual(row);
      if (mode === 'timer')
        await act(async () => {
          await vi.advanceTimersByTimeAsync(1000);
        });
      else {
        vi.setSystemTime(new Date('2026-10-18T15:00:00Z'));
        await act(async () => {
          await result.current.confirmCancellation();
        });
        expect(result.current.notice?.text).toContain('過去');
      }
      expect(result.current.cancelTarget).toBeNull();
      expect(result.current.past).toBe(true);
      expect(cancelAdminReservation).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  },
);

it.each(['GET', 'DELETE'] as const)(
  '[ADMIN-07] %s待機中の画面破棄後は応答を反映せず再取得しない',
  async (method) => {
    vi.useFakeTimers();
    try {
      const unauthorized = vi.fn();
      const { result, unmount } = renderHook(() =>
        useAdminReservations('token', unauthorized),
      );
      const date = result.current.date;
      vi.mocked(getAdminReservations).mockResolvedValue({
        date,
        reservations: [row],
      });
      let finish!: () => void;
      let pending!: Promise<void>;
      if (method === 'GET') {
        vi.mocked(getAdminReservations).mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finish = () => resolve({ date, reservations: [row] });
            }),
        );
        act(() => {
          pending = result.current.fetchReservations();
        });
      } else {
        await act(async () => {
          await result.current.fetchReservations();
        });
        act(() => result.current.openCancelDialog(row));
        vi.mocked(cancelAdminReservation).mockImplementationOnce(
          () =>
            new Promise((resolve) => {
              finish = resolve;
            }),
        );
        act(() => {
          pending = result.current.confirmCancellation();
        });
      }
      const snapshot = result.current;
      const count = vi.mocked(getAdminReservations).mock.calls.length;
      const signal = vi.mocked(getAdminReservations).mock.calls.at(-1)![2]!;
      unmount();
      expect(signal.aborted).toBe(true);
      expect(vi.getTimerCount()).toBe(0);
      await act(async () => {
        finish();
        await pending;
        await vi.advanceTimersByTimeAsync(15000);
      });
      expect(result.current).toBe(snapshot);
      expect(getAdminReservations).toHaveBeenCalledTimes(count);
      expect(unauthorized).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  },
);

it('[ADMIN-08] 取得済み一覧・通知・確認対象を日付変更でクリアする', async () => {
  const { result } = renderHook(() => useAdminReservations('token', vi.fn()));
  vi.mocked(getAdminReservations).mockResolvedValue({
    date: result.current.date,
    reservations: [row],
  });
  vi.mocked(cancelAdminReservation).mockRejectedValue(
    new ApiError(400, '取消できません'),
  );
  await act(async () => {
    await result.current.fetchReservations();
  });
  act(() => result.current.openCancelDialog(row));
  await act(async () => {
    await result.current.confirmCancellation();
  });
  act(() => result.current.openCancelDialog(row));
  expect(result.current.lastChecked).not.toBeNull();
  expect(result.current.notice).not.toBeNull();
  expect(result.current.cancelTarget).not.toBeNull();
  const count = vi.mocked(getAdminReservations).mock.calls.length;
  act(() => result.current.changeDate('2099-01-02'));
  expect(result.current).toMatchObject({
    reservations: [],
    loaded: false,
    lastChecked: null,
    notice: null,
    cancelTarget: null,
    fetchError: false,
  });
  expect(getAdminReservations).toHaveBeenCalledTimes(count);
});

it('[ADMIN-09] 座席順・取得時刻を確定し異なる日付の応答は適用しない', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-18T01:00:00Z'));
  try {
    const { result } = renderHook(() => useAdminReservations('token', vi.fn()));
    const first = { ...row, id: 'first', seatNumber: 1 };
    vi.mocked(getAdminReservations).mockResolvedValueOnce({
      date: result.current.date,
      reservations: [row, first],
    });
    await act(async () => {
      await result.current.fetchReservations();
    });
    expect(result.current.reservations).toEqual([first, row]);
    const checked = result.current.lastChecked;
    expect(checked).toEqual(new Date('2026-10-18T01:00:00Z'));
    vi.mocked(getAdminReservations).mockResolvedValueOnce({
      date: '2099-01-01',
      reservations: [],
    });
    await act(async () => {
      await result.current.fetchReservations();
    });
    expect(result.current.fetchError).toBe(true);
    expect(result.current.reservations).toEqual([first, row]);
    expect(result.current.lastChecked).toBe(checked);
    act(() => result.current.openCancelDialog(row));
    expect(result.current.cancelTarget).toBeNull();
  } finally {
    vi.useRealTimers();
  }
});
