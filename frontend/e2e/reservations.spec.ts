import { expect, test, type Page } from '@playwright/test';

async function mockApi(page: Page) {
  const reservations = new Map<string, { user: string; id: string }>([
    ['2026-10-12:5', { user: 'demo-user-1', id: 'mine-5' }],
    ['2026-10-12:8', { user: 'demo-user-2', id: 'other-8' }],
  ]);
  const state = { offline: false, conflict: false, posts: 0 };
  await page.clock.install({ time: new Date('2026-10-12T01:00:00Z') });
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const user = request.headers()['x-demo-user'];
      if (!['demo-user-1', 'demo-user-2'].includes(user)) {
        await route.fulfill({
          status: 400,
          json: { detail: '不正なユーザー' },
        });
        return;
      }
      if (request.method() === 'GET') {
        if (state.offline) {
          await route.abort();
          return;
        }
        const date = url.searchParams.get('date');
        await route.fulfill({
          json: {
            date,
            seats: Array.from({ length: 30 }, (_, i) => {
              const reservation = reservations.get(`${date}:${i + 1}`);
              return {
                seatNumber: i + 1,
                status: !reservation
                  ? 'AVAILABLE'
                  : reservation.user === user
                    ? 'MINE'
                    : 'OCCUPIED',
                ...(reservation?.user === user
                  ? { reservationId: reservation.id }
                  : {}),
              };
            }),
          },
        });
      } else if (request.method() === 'POST') {
        state.posts++;
        const { date, seatNumber } = request.postDataJSON();
        const key = `${date}:${seatNumber}`;
        if (state.conflict)
          reservations.set(key, {
            user: 'demo-user-2',
            id: `other-${seatNumber}`,
          });
        if (reservations.has(key)) {
          await route.fulfill({
            status: 409,
            json: { detail: '予約済みです。' },
          });
          return;
        }
        const id = `reservation-${seatNumber}`;
        reservations.set(key, { user, id });
        await route.fulfill({ status: 201, json: { id, date, seatNumber } });
      } else if (request.method() === 'DELETE') {
        const id = url.pathname.split('/').at(-1);
        const match = [...reservations.entries()].find(
          ([, r]) => r.id === id && r.user === user,
        );
        if (!match) {
          await route.fulfill({
            status: 404,
            json: { detail: '予約がありません。' },
          });
          return;
        }
        reservations.delete(match[0]);
        await route.fulfill({ status: 204 });
      }
    },
  );
  return { reservations, state };
}

test('確定画面の文言と予約・ユーザー切替・キャンセル・過去日', async ({
  page,
}) => {
  await mockApi(page);
  await page.goto('/');
  await expect(page.locator('.seat')).toHaveCount(30);
  await expect(
    page.getByRole('heading', { name: '座席予約', exact: true }),
  ).toBeVisible();
  await expect(page.getByText('利用する日付と座席を選択')).toHaveCount(0);
  await expect(page.getByText('複数席の同時選択はできません。')).toBeVisible();
  await expect(page.locator('.legend')).toHaveText(
    '空席予約済み予約不可✓ 選択中',
  );
  await page.screenshot({ path: 'test-results/desktop.png', fullPage: true });
  await page.getByRole('button', { name: '座席1、空席', exact: true }).click();
  await expect(
    page.getByRole('button', { name: '予約をキャンセル', exact: true }),
  ).toBeDisabled();
  await page.getByRole('button', { name: '予約する', exact: true }).click();
  await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
    '予約が完了しました',
  );
  await expect(
    page.getByRole('button', {
      name: '座席1、予約済み（自分の予約）',
      exact: true,
    }),
  ).toBeEnabled();
  await page.getByLabel('仮ユーザー').selectOption('demo-user-2');
  await expect(
    page.getByRole('button', {
      name: '座席1、予約不可（他の人の予約）',
      exact: true,
    }),
  ).toBeDisabled();
  await page.getByLabel('仮ユーザー').selectOption('demo-user-1');
  await page
    .getByRole('button', { name: '座席1、予約済み（自分の予約）', exact: true })
    .click();
  await page
    .getByRole('button', { name: '予約をキャンセル', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: '座席1、空席', exact: true }),
  ).toBeEnabled();
  await page.getByLabel('利用日').fill('2026-10-11');
  await expect(page.locator('.seat:enabled')).toHaveCount(0);
  await expect(
    page.getByText('過去の日付は、予約・キャンセルできません。'),
  ).toBeVisible();
});

test('5秒更新で選択席の変化を通知し、通信障害から再取得できる', async ({
  page,
}) => {
  const { reservations, state } = await mockApi(page);
  await page.goto('/');
  await page.getByRole('button', { name: '座席1、空席', exact: true }).click();
  reservations.set('2026-10-12:1', { user: 'demo-user-2', id: 'concurrent' });
  await page.clock.fastForward(5100);
  await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
    '座席情報が更新されました',
  );
  await expect(
    page.getByRole('button', { name: '予約する', exact: true }),
  ).toBeDisabled();
  state.offline = true;
  await page.clock.fastForward(5100);
  await expect(page.getByRole('alert')).toContainText(
    '最新の座席情報を取得できません',
  );
  await expect(page.locator('.seat:enabled')).toHaveCount(0);
  state.offline = false;
  await page.getByRole('button', { name: '再取得', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: '座席2、空席', exact: true }),
  ).toBeEnabled();
});

test('予約競合を表示し、最新の予約不可状態を反映する', async ({ page }) => {
  const { state } = await mockApi(page);
  state.conflict = true;
  await page.goto('/');
  await page.getByRole('button', { name: '座席1、空席', exact: true }).click();
  await page.getByRole('button', { name: '予約する', exact: true }).click();
  await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
    '先に予約されました',
  );
  await expect(
    page.getByRole('button', {
      name: '座席1、予約不可（他の人の予約）',
      exact: true,
    }),
  ).toBeDisabled();
  expect(state.posts).toBe(1);
});

test('320px幅でも操作可能かつ画面内に収まる', async ({ page }) => {
  await mockApi(page);
  await page.setViewportSize({ width: 320, height: 850 });
  await page.goto('/');
  await expect(page.locator('.seat')).toHaveCount(30);
  await page
    .getByRole('button', { name: '座席5、予約済み（自分の予約）', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: '予約をキャンセル', exact: true }),
  ).toBeEnabled();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(320);
  await page.screenshot({ path: 'test-results/mobile.png', fullPage: true });
});

test('[SEAT-01][SEAT-02] 3状態の配色・文字・空席数と選択枠', async ({
  page,
}) => {
  await mockApi(page);
  await page.goto('/');
  await expect(page.locator('.seat')).toHaveCount(30);
  await expect(page.getByText('空席 28 / 30')).toBeVisible();
  const free = page.getByRole('button', { name: '座席1、空席', exact: true });
  const mine = page.getByRole('button', {
    name: '座席5、予約済み（自分の予約）',
    exact: true,
  });
  const other = page.getByRole('button', {
    name: '座席8、予約不可（他の人の予約）',
    exact: true,
  });
  await page.emulateMedia({ colorScheme: 'light' });
  await expect(free).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  await expect(mine).toHaveCSS('background-color', 'rgb(227, 244, 234)');
  await expect(other).toHaveCSS('background-color', 'rgb(251, 233, 233)');
  await expect(other).toBeDisabled();
  await free.click();
  await expect(free).toHaveCSS('outline-width', '3px');
  await expect(free.locator('.selection-mark')).toHaveText('✓');
});

for (const width of [1280, 320]) {
  test(`[${width === 1280 ? 'UI-01' : 'UI-02'}] ${width}pxの列数・パネル位置・クリッピング`, async ({
    page,
  }) => {
    await mockApi(page);
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await expect(page.locator('.seat')).toHaveCount(30);
    const columns = await page
      .locator('.seat-grid')
      .evaluate(
        (el) => getComputedStyle(el).gridTemplateColumns.split(' ').length,
      );
    expect(columns).toBe(width === 1280 ? 6 : 3);
    const floor = (await page.locator('.floor').boundingBox())!;
    const panel = (await page.locator('.action-panel').boundingBox())!;
    if (width === 1280) {
      expect(panel.x).toBeGreaterThan(floor.x + floor.width);
      expect(panel.y).toBe(floor.y);
    } else expect(panel.y).toBeGreaterThan(floor.y + floor.height);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth),
    ).toBeLessThanOrEqual(width);
    expect(
      await page
        .locator('h1,h2,.legend,.seat,.action,.footnote')
        .evaluateAll((nodes) =>
          nodes.every(
            (el) =>
              el.scrollWidth <= el.clientWidth &&
              el.scrollHeight <= el.clientHeight,
          ),
        ),
    ).toBe(true);
    await page
      .getByRole('button', {
        name: '座席5、予約済み（自分の予約）',
        exact: true,
      })
      .click();
    const cancel = page.getByRole('button', {
      name: '予約をキャンセル',
      exact: true,
    });
    await cancel.scrollIntoViewIfNeeded();
    await expect(cancel).toBeInViewport();
    await expect(cancel).toBeEnabled();
    await page.screenshot({
      path: `test-results/matrix-${width}.png`,
      fullPage: true,
    });
    await cancel.click();
    await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
      'キャンセルしました',
    );
  });
}

test('[UI-03] Tab・Enter・Spaceだけで予約とキャンセル', async ({ page }) => {
  const { state } = await mockApi(page);
  await page.goto('/');
  await expect(page.locator('.seat')).toHaveCount(30);
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('利用日')).toBeFocused();
  const free = page.getByRole('button', { name: '座席1、空席', exact: true });
  for (
    let i = 0;
    i < 12 && !(await free.evaluate((el) => el === document.activeElement));
    i++
  )
    await page.keyboard.press('Tab');
  await expect(free).toBeFocused();
  await expect(free).toHaveCSS('outline-style', 'solid');
  await page.keyboard.press('Space');
  await expect(free).toHaveAttribute('aria-pressed', 'true');
  const reserve = page.getByRole('button', { name: '予約する', exact: true });
  for (
    let i = 0;
    i < 35 && !(await reserve.evaluate((el) => el === document.activeElement));
    i++
  ) {
    await page.keyboard.press('Tab');
    expect(
      await page.evaluate(() => document.activeElement?.matches(':disabled')),
    ).toBe(false);
  }
  await expect(reserve).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
    '予約が完了',
  );
  expect(state.posts).toBe(1);
  const mine = page.getByRole('button', {
    name: '座席1、予約済み（自分の予約）',
    exact: true,
  });
  for (
    let i = 0;
    i < 45 && !(await mine.evaluate((el) => el === document.activeElement));
    i++
  )
    await page.keyboard.press('Tab');
  await expect(mine).toBeFocused();
  await page.keyboard.press('Enter');
  const cancel = page.getByRole('button', {
    name: '予約をキャンセル',
    exact: true,
  });
  for (
    let i = 0;
    i < 35 && !(await cancel.evaluate((el) => el === document.activeElement));
    i++
  )
    await page.keyboard.press('Tab');
  await expect(cancel).toBeFocused();
  await page.keyboard.press('Space');
  await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
    'キャンセルしました',
  );
});

test('[UI-04] アクセシブルな名前・選択状態・通知領域', async ({ page }) => {
  const { state } = await mockApi(page);
  await page.goto('/');
  const free = page.getByRole('button', { name: '座席1、空席', exact: true });
  await expect(free).toHaveAttribute('aria-pressed', 'false');
  await free.click();
  await expect(free).toHaveAttribute('aria-pressed', 'true');
  await expect(page.locator('.selection')).toHaveAttribute(
    'aria-live',
    'polite',
  );
  await expect(
    page.getByRole('button', {
      name: '座席5、予約済み（自分の予約）',
      exact: true,
    }),
  ).toBeEnabled();
  await expect(
    page.getByRole('button', {
      name: '座席8、予約不可（他の人の予約）',
      exact: true,
    }),
  ).toBeDisabled();
  await page.getByRole('button', { name: '予約する', exact: true }).click();
  await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
    '完了',
  );
  expect(
    await page
      .locator('.sync')
      .evaluate(
        (el) =>
          el.closest('[aria-live], [role="status"], [role="alert"]') === null,
      ),
  ).toBe(true);
  state.offline = true;
  await page.clock.fastForward(5100);
  await expect(page.getByRole('alert')).toContainText(
    '最新の座席情報を取得できません',
  );
});

for (const theme of ['light', 'dark'] as const) {
  test(`[UI-05] ${theme}の状態色と文字コントラスト`, async ({ page }) => {
    await mockApi(page);
    await page.emulateMedia({ colorScheme: theme });
    await page.goto('/');
    await expect(page.locator('.seat')).toHaveCount(30);
    await page
      .getByRole('button', { name: '座席1、空席', exact: true })
      .click();
    const colors = await page.locator('.seat').evaluateAll((nodes) =>
      nodes.map((el) => {
        const style = getComputedStyle(el);
        const luminance = (color: string) => {
          const channels = color
            .match(/[\d.]+/g)!
            .slice(0, 3)
            .map(Number)
            .map((n) => {
              n /= 255;
              return n <= 0.04045 ? n / 12.92 : ((n + 0.055) / 1.055) ** 2.4;
            });
          return (
            channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
          );
        };
        const a = luminance(style.color),
          b = luminance(style.backgroundColor);
        return {
          background: style.backgroundColor,
          ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05),
        };
      }),
    );
    expect(
      new Set([
        colors[0].background,
        colors[4].background,
        colors[7].background,
      ]).size,
    ).toBe(3);
    for (const color of colors) expect(color.ratio).toBeGreaterThanOrEqual(4.5);
    await expect(page.locator('.seat[aria-pressed="true"]')).toHaveCSS(
      'outline-width',
      '3px',
    );
    await expect(
      page.getByRole('button', { name: '予約をキャンセル', exact: true }),
    ).toBeDisabled();
    await page.screenshot({
      path: `test-results/matrix-${theme}.png`,
      fullPage: true,
    });
  });
}
