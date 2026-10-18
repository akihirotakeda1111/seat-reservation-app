import { expect, test } from '@playwright/test';
import { login } from './login';

test('[ADMIN-UI-01] 管理者の一覧・取消確認・過去日・画面切替', async ({
  page,
}) => {
  const records = [{ id: 'r-1', seatNumber: 5, userId: 'another-users-id' }];
  let deletes = 0;
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      expect(request.headers()['authorization']).toMatch(/^Bearer /);
      if (url.pathname === '/api/seats')
        return route.fulfill({
          json: { date: url.searchParams.get('date'), seats: [] },
        });
      if (request.method() === 'DELETE') {
        expect(url.pathname).toBe('/api/admin/reservations/r-1');
        deletes++;
        records.length = 0;
        return route.fulfill({ status: 204 });
      }
      expect(url.pathname).toBe('/api/admin/reservations');
      return route.fulfill({
        json: { date: url.searchParams.get('date'), reservations: records },
      });
    },
  );
  await login(page, 'admin-user');
  await page.getByRole('button', { name: '予約管理へ' }).click();
  await expect(page).toHaveURL(/\/admin\/reservations$/);
  await expect(page.getByRole('table')).toHaveCount(0);
  await page.getByRole('button', { name: '予約一覧を取得' }).click();
  await expect(
    page.getByRole('cell', { name: 'another-users-id' }),
  ).toBeVisible();
  await page.getByRole('button', { name: '座席5の予約をキャンセル' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await page.getByRole('button', { name: '戻る', exact: true }).click();
  expect(deletes).toBe(0);
  await page.getByRole('button', { name: '座席5の予約をキャンセル' }).click();
  await page
    .getByRole('button', { name: '予約をキャンセル', exact: true })
    .click();
  await expect(page.getByText('指定日の予約はありません。')).toBeVisible();
  expect(deletes).toBe(1);
  records.push({ id: 'r-1', seatNumber: 5, userId: 'another-users-id' });
  await page.getByLabel('利用日').fill('2000-01-01');
  await page.getByRole('button', { name: '予約一覧を取得' }).click();
  await expect(
    page.getByRole('button', { name: '座席5の予約をキャンセル' }),
  ).toBeDisabled();
  await page.screenshot({
    path: 'test-results/admin-reservations.png',
    fullPage: true,
  });
  await page.getByRole('button', { name: '予約画面へ' }).click();
  await expect(page).toHaveURL(/\/reservations$/);
});
test('[ADMIN-UI-02] 一般ユーザーには管理導線がなく直接アクセスでも管理APIを呼ばない', async ({
  page,
}) => {
  let adminRequests = 0;
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    (route) => {
      const url = new URL(route.request().url());
      if (url.pathname.startsWith('/api/admin/')) adminRequests++;
      return route.fulfill({
        json: { date: url.searchParams.get('date'), seats: [] },
      });
    },
  );
  await login(page);
  await expect(page.getByRole('button', { name: '予約管理へ' })).toHaveCount(0);
  await page.evaluate(() => {
    history.pushState(null, '', '/admin/reservations');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await expect(page).toHaveURL(/\/reservations$/);
  expect(adminRequests).toBe(0);
});

// 管理画面の表示とHTTP応答を結合し、フック単体では確認できない操作を検証する。
async function adminScreen(page: import('@playwright/test').Page) {
  const state = {
    getStatus: 200,
    deleted: false,
    deletes: 0,
    failAfterDelete: false,
    holdDelete: false,
    release: () => {},
  };
  const user = '12345678-1234-1234-1234-123456789012';
  await page.clock.install({ time: new Date('2026-10-18T01:00:00Z') });
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const url = new URL(route.request().url());
      if (url.pathname === '/api/seats')
        return route.fulfill({
          json: { date: url.searchParams.get('date'), seats: [] },
        });
      if (route.request().method() === 'DELETE') {
        state.deletes++;
        if (state.holdDelete)
          await new Promise<void>((resolve) => {
            state.release = resolve;
          });
        state.deleted = true;
        if (state.failAfterDelete) state.getStatus = 500;
        return route.fulfill({ status: 204 });
      }
      if (state.getStatus !== 200)
        return route.fulfill({
          status: state.getStatus,
          json: { detail: '取得できません' },
        });
      return route.fulfill({
        json: {
          date: url.searchParams.get('date'),
          reservations: state.deleted
            ? []
            : [
                { id: 'r-8', seatNumber: 8, userId: 'someone-else' },
                { id: 'r-5', seatNumber: 5, userId: user },
              ],
        },
      });
    },
  );
  await login(page, 'admin-user');
  await page.getByRole('button', { name: '予約管理へ' }).click();
  await page.clock.setFixedTime(new Date('2026-10-18T01:00:00Z'));
  await page.getByRole('button', { name: '予約一覧を取得' }).click();
  await expect(
    page.getByRole('cell', { name: user, exact: true }),
  ).toBeVisible();
  return { state, user };
}

for (const afterDelete of [false, true]) {
  test(`[ADMIN-UI-03] ${afterDelete ? '取消成功後' : '一覧取得'}の失敗から手動再取得で復帰`, async ({
    page,
  }) => {
    const { state } = await adminScreen(page);
    if (afterDelete) {
      state.failAfterDelete = true;
      await page
        .getByRole('button', { name: '座席5の予約をキャンセル' })
        .click();
      await page
        .getByRole('button', { name: '予約をキャンセル', exact: true })
        .click();
      await expect(
        page.getByRole('status', { name: '操作結果' }),
      ).toContainText('キャンセルしました');
    } else {
      state.getStatus = 500;
      await page.getByRole('button', { name: '予約一覧を取得' }).click();
    }
    await expect(page.getByRole('alert')).toContainText(
      '最新の予約一覧を取得できません',
    );
    await expect(
      page.getByRole('button', { name: '座席5の予約をキャンセル' }),
    ).toBeDisabled();
    state.getStatus = 200;
    await page.getByRole('button', { name: '再取得', exact: true }).click();
    await expect(page.getByRole('alert')).toHaveCount(0);
    if (afterDelete) {
      await expect(page.getByText('指定日の予約はありません。')).toBeVisible();
      expect(state.deletes).toBe(1);
    } else
      await expect(
        page.getByRole('button', { name: '座席5の予約をキャンセル' }),
      ).toBeEnabled();
  });
}

for (const width of [1280, 320]) {
  test(`[ADMIN-UI-04] ${width}pxの一覧・確認内容・キーボード・取消中の操作抑止`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    const { state, user } = await adminScreen(page);
    await expect(page.getByRole('heading', { name: /2件/ })).toBeVisible();
    await expect(page.getByText(/最終取得 10:00:00/)).toBeVisible();
    const rows = page.getByRole('row');
    await expect(rows.nth(1)).toContainText(user);
    await expect(rows.nth(2)).toContainText('someone-else');
    const trigger = page.getByRole('button', {
      name: '座席5の予約をキャンセル',
    });
    await trigger.click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('2026年10月18日');
    await expect(dialog).toContainText('座席番号：05');
    await expect(dialog).toContainText(user);
    await expect(dialog).not.toContainText('someone-else');
    const back = dialog.getByRole('button', { name: '戻る', exact: true });
    const confirm = dialog.getByRole('button', {
      name: '予約をキャンセル',
      exact: true,
    });
    await expect(back).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(confirm).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(back).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
    expect(state.deletes).toBe(0);
    await page.keyboard.press('Enter');
    await expect(dialog).toBeVisible();
    const box = await dialog.boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(width);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    state.holdDelete = true;
    await confirm.click();
    await expect(dialog.getByRole('status')).toContainText('キャンセル中');
    await expect(back).toBeDisabled();
    await expect(confirm).toBeDisabled();
    await expect(page.getByLabel('利用日')).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(dialog).toBeVisible();
    expect(state.deletes).toBe(1);
    state.release();
    await expect(dialog).toHaveCount(0);
  });
}

test('[ADMIN-UI-05] 管理画面の401でログインへ戻り一覧を破棄する', async ({
  page,
}) => {
  const { state, user } = await adminScreen(page);
  state.getStatus = 401;
  await page.getByRole('button', { name: '予約一覧を取得' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('alert')).toContainText('有効期限');
  await expect(page.getByRole('table')).toHaveCount(0);
  await expect(page.getByText(user)).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: 'ログイン', exact: true }),
  ).toBeEnabled();
});

test('[ADMIN-UI-06] 未認証で管理URLを直接開いてもAPIを呼ばない', async ({
  page,
}) => {
  const requests: string[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname.startsWith('/api/'))
      requests.push(request.url());
  });
  await page.goto('/admin/reservations');
  await expect(page).toHaveURL(/\/login$/);
  await expect(
    page.getByRole('button', { name: 'ログイン', exact: true }),
  ).toBeEnabled();
  expect(requests).toEqual([]);
});
