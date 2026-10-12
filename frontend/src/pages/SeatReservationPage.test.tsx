import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SeatReservationPage } from './SeatReservationPage';
import {
  A,
  B,
  D,
  NEXT,
  PREV,
  apiHarness,
  deferred,
  initialSeats,
  json,
  problem,
  seatResponse,
} from '../test/apiHarness';

let api: ReturnType<typeof apiHarness>;
const reserve = () => screen.getByRole('button', { name: '予約する' });
const cancel = () => screen.getByRole('button', { name: '予約をキャンセル' });
const seat = (n: number) =>
  screen.getByRole('button', { name: new RegExp(`^座席${n}、`) });
const result = () => screen.getByRole('status', { name: '操作結果' });
const selected = () => document.querySelectorAll('.seat[aria-pressed="true"]');
const settle = async () => {
  await act(async () => {});
};
const advance = async (ms: number) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};
async function open() {
  render(<SeatReservationPage />);
  await settle();
}
async function changeDate(value: string) {
  fireEvent.change(screen.getByLabelText('利用日'), { target: { value } });
  await settle();
}
async function changeUser(value: string) {
  fireEvent.change(screen.getByLabelText('仮ユーザー'), { target: { value } });
  await settle();
}
function disabledActions() {
  expect(reserve()).toBeDisabled();
  expect(cancel()).toBeDisabled();
}
function noSelection() {
  expect(selected()).toHaveLength(0);
  expect(screen.getByText('未選択')).toBeInTheDocument();
}
async function perform(action: 'POST' | 'DELETE') {
  fireEvent.click(seat(action === 'POST' ? 1 : 5));
  fireEvent.click(action === 'POST' ? reserve() : cancel());
  await settle();
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-12T01:00:00Z'));
  api = apiHarness();
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('マトリクス：画面と実APIクライアント（fetch境界のみ代替）', () => {
  it('[INIT-01] 初期日付・ユーザー・30席・未選択・最終確認', async () => {
    await open();
    expect(screen.getByLabelText('利用日')).toHaveValue(D);
    expect(screen.getByLabelText('仮ユーザー')).toHaveValue(A);
    expect(api.reads()[0].url.searchParams.get('date')).toBe(D);
    expect(api.reads()[0].init.headers).toMatchObject({ 'X-Demo-User': A });
    expect(document.querySelectorAll('.seat')).toHaveLength(30);
    noSelection();
    disabledActions();
    expect(
      screen.getByText('最終確認 10:00:00（日本時間）'),
    ).toBeInTheDocument();
  });
  it('[INIT-02] 確定文言', async () => {
    await open();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      /^座席予約$/,
    );
    expect(
      screen.queryByText('利用する日付と座席を選択'),
    ).not.toBeInTheDocument();
    expect(
      within(screen.getByRole('region', { name: '座席一覧' })).getByRole(
        'heading',
      ),
    ).toHaveTextContent(/^2026年10月12日\(月\)$/);
    expect(document.querySelector('.legend')).toHaveTextContent(
      '空席予約済み予約不可✓ 選択中',
    );
    expect(seat(1)).toHaveTextContent('空席');
    expect(seat(5)).toHaveTextContent('予約済み');
    expect(seat(8)).toHaveTextContent('予約不可');
    expect(
      screen.getByText('複数席の同時選択はできません。'),
    ).toBeInTheDocument();
  });
  it('[INIT-03] 初回GET保留中に空席を表示しない', async () => {
    api.state.read = () => new Promise(() => {});
    await open();
    expect(screen.getByText('座席情報を取得しています…')).toBeInTheDocument();
    expect(document.querySelectorAll('.seat')).toHaveLength(0);
    disabledActions();
    fireEvent.click(reserve());
    fireEvent.click(cancel());
    expect(api.writes()).toHaveLength(0);
  });
  it('[INIT-04] 31席をAPIから表示する', async () => {
    api.state.seats = initialSeats(31);
    await open();
    expect(document.querySelectorAll('.seat')).toHaveLength(31);
    expect(screen.getByText('空席 29 / 31')).toBeInTheDocument();
    fireEvent.click(seat(31));
    expect(seat(31)).toHaveAttribute('aria-pressed', 'true');
    expect(reserve()).toBeEnabled();
  });
  it('[INIT-05] 空配列を通信障害と区別する', async () => {
    api.state.seats = [];
    await open();
    expect(
      screen.getByText('登録されている座席はありません。'),
    ).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    disabledActions();
  });
  it('[DATE-01] 日付変更で旧情報を即時破棄し、取得中は操作不可', async () => {
    await open();
    fireEvent.click(seat(1));
    const pending = deferred<Response>();
    api.state.read = () => pending.promise;
    await changeDate(NEXT);
    noSelection();
    disabledActions();
    expect(document.querySelectorAll('.seat')).toHaveLength(0);
    expect(api.reads().at(-1)!.url.searchParams.get('date')).toBe(NEXT);
    pending.resolve(
      seatResponse([{ seatNumber: 31, status: 'OCCUPIED' }], NEXT),
    );
    await settle();
    expect(seat(31)).toHaveTextContent('予約不可');
    expect(screen.queryByRole('button', { name: /^座席1、/ })).toBeNull();
  });
  it('[DATE-02] 未入力で選択解除し、新規通信を止める', async () => {
    await open();
    fireEvent.click(seat(1));
    const count = api.calls.length;
    await changeDate('');
    await advance(15000);
    noSelection();
    disabledActions();
    expect(screen.getByText('利用日を指定してください。')).toBeInTheDocument();
    fireEvent.click(reserve());
    fireEvent.click(cancel());
    expect(api.calls).toHaveLength(count);
  });
  it('[DATE-04] 過去日の3状態は閲覧のみ', async () => {
    await open();
    await changeDate(PREV);
    expect(api.reads().at(-1)!.url.searchParams.get('date')).toBe(PREV);
    expect(seat(1)).toHaveTextContent('空席');
    expect(seat(5)).toHaveTextContent('予約済み');
    expect(seat(8)).toHaveTextContent('予約不可');
    for (const button of document.querySelectorAll('.seat'))
      expect(button).toBeDisabled();
    disabledActions();
    expect(
      screen.getByText('過去の日付は、予約・キャンセルできません。'),
    ).toBeInTheDocument();
    expect(api.writes()).toHaveLength(0);
  });
  it.each([D, NEXT])(
    '[DATE-05] %s は空席予約と自席キャンセルのみ可能',
    async (day) => {
      await open();
      await changeDate(day);
      fireEvent.click(seat(1));
      expect(reserve()).toBeEnabled();
      expect(cancel()).toBeDisabled();
      fireEvent.click(seat(5));
      expect(cancel()).toBeEnabled();
      expect(reserve()).toBeDisabled();
    },
  );
  it('[USER-01] A→B→Aで選択・予約IDを破棄し再取得', async () => {
    await open();
    fireEvent.click(seat(5));
    const pending = deferred<Response>();
    api.state.read = () => pending.promise;
    await changeUser(B);
    noSelection();
    disabledActions();
    expect(api.reads().at(-1)!.init.headers).toMatchObject({
      'X-Demo-User': B,
    });
    pending.resolve(seatResponse([{ seatNumber: 5, status: 'OCCUPIED' }]));
    await settle();
    expect(seat(5)).toBeDisabled();
    expect(seat(5)).toHaveTextContent('予約不可');
    api.state.read = undefined;
    await changeUser(A);
    noSelection();
    disabledActions();
    expect(api.reads().at(-1)!.init.headers).toMatchObject({
      'X-Demo-User': A,
    });
    expect(seat(5)).toHaveTextContent('予約済み');
    fireEvent.click(cancel());
    expect(api.writes()).toHaveLength(0);
  });
  it.each([
    [1, true],
    [5, false],
  ] as const)(
    '[SEAT-02][SEAT-03] 座席%s選択は書き込みなし',
    async (n, available) => {
      await open();
      fireEvent.click(seat(n));
      expect(seat(n)).toHaveAttribute('aria-pressed', 'true');
      expect(seat(n).querySelector('.selection-mark')).toHaveTextContent('✓');
      expect(
        within(
          screen.getByRole('complementary', { name: '予約操作' }),
        ).getByText(`座席 ${String(n).padStart(2, '0')}`),
      ).toBeInTheDocument();
      expect(
        within(screen.getByRole('complementary')).getByText(
          '2026年10月12日(月)',
        ),
      ).toBeInTheDocument();
      expect(reserve().hasAttribute('disabled')).toBe(!available);
      expect(cancel().hasAttribute('disabled')).toBe(available);
      expect(api.writes()).toHaveLength(0);
    },
  );
  it('[SEAT-04] 他席は選択・操作不可', async () => {
    await open();
    fireEvent.click(seat(8));
    noSelection();
    disabledActions();
    fireEvent.click(reserve());
    fireEvent.click(cancel());
    expect(api.writes()).toHaveLength(0);
  });
  it('[SEAT-05] 同一席再クリックで解除', async () => {
    await open();
    fireEvent.click(seat(1));
    fireEvent.click(seat(1));
    noSelection();
    disabledActions();
  });
  it('[SEAT-06] 別席を選ぶと単一選択を維持', async () => {
    await open();
    fireEvent.click(seat(1));
    fireEvent.click(seat(2));
    expect(selected()).toHaveLength(1);
    expect(seat(2)).toHaveAttribute('aria-pressed', 'true');
    expect(seat(1)).toHaveAttribute('aria-pressed', 'false');
  });
  it.each(['POST', 'DELETE'] as const)(
    '[RES-01][DEL-01][ACT-04] %s成功と通知保持・送信内容',
    async (action) => {
      await open();
      const count = api.reads().length;
      await perform(action);
      expect(api.writes()).toHaveLength(1);
      const request = api.writes()[0];
      expect(request.method).toBe(action);
      expect(request.init.headers).toMatchObject({ 'X-Demo-User': A });
      if (action === 'POST') {
        expect(request.url.pathname).toBe('/api/reservations');
        expect(request.init.headers).toMatchObject({
          'Content-Type': 'application/json',
        });
        expect(JSON.parse(String(request.init.body))).toEqual({
          date: D,
          seatNumber: 1,
        });
        expect(seat(1)).toHaveTextContent('予約済み');
        expect(result()).toHaveTextContent('座席 01 の予約が完了しました。');
      } else {
        expect(request.url.pathname).toBe('/api/reservations/mine-5');
        expect(request.init.body).toBeUndefined();
        expect(seat(5)).toHaveTextContent('空席');
        expect(result()).toHaveTextContent(
          '座席 05 の予約をキャンセルしました。',
        );
      }
      noSelection();
      expect(api.reads().length).toBeGreaterThan(count);
      const notice = result().textContent;
      await advance(5000);
      expect(result()).toHaveTextContent(notice!);
      expect(api.writes()).toHaveLength(1);
    },
  );
  it('[RES-02] 409で最新状態へ更新し自動再送しない', async () => {
    api.state.write = () => {
      api.state.seats[0] = { seatNumber: 1, status: 'OCCUPIED' };
      return problem(409, '競合');
    };
    await open();
    await perform('POST');
    noSelection();
    expect(result()).toHaveTextContent('先に予約されました');
    expect(seat(1)).toHaveTextContent('予約不可');
    expect(seat(1)).toBeDisabled();
    await advance(15000);
    expect(api.writes()).toHaveLength(1);
  });
  it.each([
    ['RES-03', 'POST', 400, '入力不正です。', '入力不正です。'],
    ['DEL-02', 'DELETE', 404, '見つかりません', '対象の予約は見つかりません'],
    [
      'DEL-03',
      'DELETE',
      403,
      '指定された予約はキャンセルできません。',
      '指定された予約はキャンセルできません。',
    ],
    [
      'DEL-04',
      'DELETE',
      400,
      '過去の予約はキャンセルできません。',
      '過去の予約はキャンセルできません。',
    ],
  ] as const)(
    '[%s] %s %sの表示と再取得',
    async (_id, action, status, detail, expected) => {
      api.state.write = () => problem(status, detail);
      await open();
      const count = api.reads().length;
      await perform(action);
      expect(result()).toHaveTextContent(expected);
      expect(result()).toHaveAttribute('data-kind', 'error');
      noSelection();
      expect(api.reads().length).toBeGreaterThan(count);
      expect(api.writes()).toHaveLength(1);
    },
  );
  it.each(['POST', 'DELETE'] as const)(
    '[ACT-01][RES-04][DEL-05] %s待機中は全入力・操作無効',
    async (action) => {
      const pending = deferred<Response>();
      api.state.write = () => pending.promise;
      await open();
      await perform(action);
      expect(screen.getByText('処理しています…')).toBeInTheDocument();
      expect(screen.getByLabelText('利用日')).toBeDisabled();
      expect(screen.getByLabelText('仮ユーザー')).toBeDisabled();
      for (const button of document.querySelectorAll('.seat'))
        expect(button).toBeDisabled();
      disabledActions();
      fireEvent.change(screen.getByLabelText('利用日'), {
        target: { value: NEXT },
      });
      fireEvent.change(screen.getByLabelText('仮ユーザー'), {
        target: { value: B },
      });
      fireEvent.click(reserve());
      fireEvent.click(cancel());
      fireEvent.click(seat(2));
      expect(api.writes()).toHaveLength(1);
      expect(screen.getByLabelText('利用日')).toHaveValue(D);
      expect(screen.getByLabelText('仮ユーザー')).toHaveValue(A);
      const originalSeatNumber = action === 'POST' ? 1 : 5;
      expect(selected()).toHaveLength(1);
      expect(seat(originalSeatNumber)).toHaveAttribute('aria-pressed', 'true');
      expect(seat(2)).toHaveAttribute('aria-pressed', 'false');
      expect(
        within(
          screen.getByRole('complementary', { name: '予約操作' }),
        ).getByText(`座席 ${String(originalSeatNumber).padStart(2, '0')}`),
      ).toBeInTheDocument();
      pending.resolve(
        action === 'POST'
          ? json({ id: 'id', date: D, seatNumber: 1 }, 201)
          : new Response(null, { status: 204 }),
      );
      const before = api.reads().length;
      await settle();
      noSelection();
      expect(api.reads().length).toBeGreaterThan(before);
    },
  );
  for (const action of ['POST', 'DELETE'] as const) {
    it.each(['切断', 'タイムアウト', '500'] as const)(
      `[ACT-02] ${action}の%sは結果不明、再送なし`,
      async (failure) => {
        api.state.write = () =>
          failure === '切断'
            ? Promise.reject(new TypeError('network'))
            : failure === '500'
              ? problem(500, 'server error')
              : new Promise(() => {});
        await open();
        const before = api.reads().length;
        await perform(action);
        if (failure === 'タイムアウト') {
          await advance(9999);
          expect(screen.getByText('処理しています…')).toBeInTheDocument();
          await advance(1);
        }
        expect(result()).toHaveTextContent('操作の結果を確認できません');
        noSelection();
        expect(api.reads().length).toBeGreaterThan(before);
        await advance(15000);
        expect(api.writes()).toHaveLength(1);
        if (failure === 'タイムアウト')
          expect(api.writes()[0].init.signal?.aborted).toBe(true);
      },
    );
    it(`[ACT-03] ${action}成功と取得失敗を区別`, async () => {
      await open();
      api.state.write = () => {
        api.state.read = () => Promise.reject(new Error('offline'));
        return action === 'POST'
          ? json({ id: 'id', date: D, seatNumber: 1 }, 201)
          : new Response(null, { status: 204 });
      };
      await perform(action);
      expect(result()).toHaveAttribute('data-kind', 'success');
      expect(screen.getByRole('alert')).toHaveTextContent(
        '最新の座席情報を取得できません',
      );
      noSelection();
      disabledActions();
      await advance(15000);
      expect(api.writes()).toHaveLength(1);
    });
  }
  it.each(['切断', '500'] as const)('[NET-01] 初回GETの%s', async (failure) => {
    api.state.read = () =>
      failure === '切断'
        ? Promise.reject(new Error('offline'))
        : problem(500, 'error');
    await open();
    expect(
      screen.getByText('座席情報を取得できませんでした。'),
    ).toBeInTheDocument();
    expect(
      screen.queryByText('登録されている座席はありません。'),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '再取得' })).toBeEnabled();
    expect(screen.getByText('最終確認 —（日本時間）')).toBeInTheDocument();
    expect(document.querySelectorAll('.seat')).toHaveLength(0);
    disabledActions();
  });
  it('[NET-02][NET-03] 失敗で旧情報・時刻を保持し手動復帰', async () => {
    await open();
    fireEvent.click(seat(1));
    api.state.read = () => Promise.reject(new Error('offline'));
    await advance(5000);
    noSelection();
    disabledActions();
    expect(document.querySelectorAll('.seat')).toHaveLength(30);
    expect(seat(5)).toHaveTextContent('予約済み');
    expect(
      screen.getByText('最終確認 10:00:00（日本時間）'),
    ).toBeInTheDocument();
    expect(screen.getByText('● 更新失敗 · 再接続を待機中')).toBeInTheDocument();
    const pending = deferred<Response>();
    api.state.read = () => pending.promise;
    fireEvent.click(screen.getByRole('button', { name: '再取得' }));
    await settle();
    expect(screen.getByRole('button', { name: '再取得中…' })).toBeDisabled();
    pending.resolve(seatResponse([{ seatNumber: 31, status: 'AVAILABLE' }]));
    await settle();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(seat(31)).toBeEnabled();
    expect(
      screen.getByText('最終確認 10:00:05（日本時間）'),
    ).toBeInTheDocument();
  });
  it('[NET-04] 自動再取得で復帰', async () => {
    api.state.read = () => Promise.reject(new Error('offline'));
    await open();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    api.state.read = undefined;
    await advance(5000);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(seat(1)).toBeEnabled();
  });
  it('[NET-05] GET10秒タイムアウト後も更新継続', async () => {
    api.state.read = () => new Promise(() => {});
    await open();
    await advance(9999);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await advance(1);
    expect(api.reads()[0].init.signal?.aborted).toBe(true);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    disabledActions();
    api.state.read = undefined;
    await advance(5000);
    expect(seat(1)).toBeEnabled();
    expect(api.reads()).toHaveLength(2);
  });
  it('[NET-06] dateの異なる応答を表示しない', async () => {
    api.state.read = () => seatResponse(initialSeats(), NEXT);
    await open();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(document.querySelectorAll('.seat')).toHaveLength(0);
    disabledActions();
  });
});
