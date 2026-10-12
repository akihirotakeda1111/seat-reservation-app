import { execFileSync } from 'node:child_process';
import { expect, test, type Page } from '@playwright/test';
import { jaToday } from '../src/utils/jaDate';

const A = 'demo-user-1',
  B = 'demo-user-2';
let day: string;
function sql(query: string): string {
  const name = process.env.FRONTEND_TEST_CONTAINER;
  if (!name || !/^seat-frontend-test-[0-9a-f-]{36}$/.test(name))
    throw new Error('テスト専用DBが設定されていません');
  const label = execFileSync(
    'docker',
    [
      'inspect',
      '--format',
      '{{ index .Config.Labels "seat-reservation.frontend-test" }}',
      name,
    ],
    { encoding: 'utf8', windowsHide: true },
  ).trim();
  if (label !== 'true')
    throw new Error('テスト専用コンテナが存在しないため失敗しました');
  return execFileSync(
    'docker',
    [
      'exec',
      '-i',
      name,
      'psql',
      '-X',
      '-U',
      'frontend_test',
      '-d',
      'frontend_test',
      '-v',
      'ON_ERROR_STOP=1',
      '-At',
    ],
    {
      input: query,
      encoding: 'utf8',
      windowsHide: true,
    },
  ).trim();
}
const rows = () =>
  sql(
    'SELECT row_to_json(r) FROM reservations r ORDER BY reservation_date,seat_number;',
  );
const seat = (page: Page, n: number) =>
  page.getByRole('button', { name: new RegExp(`^座席${n}、`) });
async function open(page: Page, user = A) {
  await page.goto('/');
  await expect(page.locator('.seat')).toHaveCount(30);
  if (user !== A) await page.getByLabel('仮ユーザー').selectOption(user);
  await expect(seat(page, 1)).toBeEnabled();
}
async function write(
  page: Page,
  method: string,
  action: string,
  expected: number,
) {
  const response = page.waitForResponse(
    (r) =>
      r.url().includes('/api/reservations') && r.request().method() === method,
  );
  await page.getByRole('button', { name: action, exact: true }).click();
  const received = await response;
  expect(received.status()).toBe(expected);
  return received;
}

test.beforeEach(() => {
  day = jaToday();
  sql('DELETE FROM reservations;');
});

test('[INT-01] ブラウザ→実API→DBで登録・切替・削除、他予約保持', async ({
  page,
}) => {
  sql(
    `INSERT INTO reservations(id,reservation_date,seat_number,user_id) VALUES ('11111111-1111-4111-8111-111111111111','${day}',5,'${A}');`,
  );
  const before = rows();
  const get = page.waitForResponse(
    (r) => r.url().includes('/api/seats') && r.status() === 200,
  );
  await open(page);
  expect((await get).status()).toBe(200);
  await seat(page, 1).click();
  const created = await write(page, 'POST', '予約する', 201);
  const body = await created.json();
  await expect(seat(page, 1)).toHaveText(/予約済み/);
  expect(
    sql(
      `SELECT id || ':' || user_id FROM reservations WHERE reservation_date='${day}' AND seat_number=1;`,
    ),
  ).toBe(`${body.id}:${A}`);
  await page.getByLabel('仮ユーザー').selectOption(B);
  await expect(seat(page, 1)).toHaveText(/予約不可/);
  await expect(seat(page, 1)).toBeDisabled();
  await page.getByLabel('仮ユーザー').selectOption(A);
  await expect(seat(page, 1)).toHaveText(/予約済み/);
  await seat(page, 1).click();
  await write(page, 'DELETE', '予約をキャンセル', 204);
  await expect(seat(page, 1)).toHaveText(/空席/);
  expect(rows()).toBe(before);
});

test('[INT-02] 別タブの予約を反映して選択解除', async ({ page, context }) => {
  await open(page);
  await seat(page, 1).click();
  const other = await context.newPage();
  await open(other, B);
  await seat(other, 1).click();
  await write(other, 'POST', '予約する', 201);
  await page.bringToFront();
  await expect(seat(page, 1)).toHaveText(/予約不可/, { timeout: 10000 });
  await expect(seat(page, 1)).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
    '座席情報が更新されました',
  );
  expect(
    sql(
      `SELECT user_id FROM reservations WHERE reservation_date='${day}' AND seat_number=1;`,
    ),
  ).toBe(B);
  await other.close();
});

test('[INT-03] 実APIの先行予約で409、DBはBの1件だけ', async ({
  page,
  context,
}) => {
  await open(page);
  await seat(page, 1).click();
  let hold = true;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route(
    (url) => url.pathname === '/api/seats',
    async (route) => {
      if (hold) await gate;
      await route.continue();
    },
  );
  const other = await context.newPage();
  try {
    await open(other, B);
    await seat(other, 1).click();
    await write(other, 'POST', '予約する', 201);
    const post = await write(page, 'POST', '予約する', 409);
    expect((await post.json()).status).toBe(409);
    hold = false;
    release();
    await expect(page.getByRole('status', { name: '操作結果' })).toContainText(
      '先に予約されました',
    );
    await expect(seat(page, 1)).toHaveText(/予約不可/);
    expect(
      sql(
        `SELECT count(*) || ':' || min(user_id) FROM reservations WHERE reservation_date='${day}' AND seat_number=1;`,
      ),
    ).toBe(`1:${B}`);
  } finally {
    hold = false;
    release();
    await other.close();
  }
});

test('[INT-04] 過去日の3状態を表示し書き込みなし・DB不変', async ({ page }) => {
  const previous = new Date(`${day}T00:00:00Z`);
  previous.setUTCDate(previous.getUTCDate() - 1);
  const past = previous.toISOString().slice(0, 10);
  sql(`INSERT INTO reservations(id,reservation_date,seat_number,user_id) VALUES
    ('11111111-1111-4111-8111-111111111111','${past}',5,'${A}'),
    ('22222222-2222-4222-8222-222222222222','${past}',8,'${B}');`);
  const before = rows();
  const writes: string[] = [];
  page.on('request', (request) => {
    if (['POST', 'DELETE'].includes(request.method()))
      writes.push(request.url());
  });
  await open(page);
  const response = page.waitForResponse(
    (r) => r.url().includes(`/api/seats?date=${past}`) && r.status() === 200,
  );
  await page.getByLabel('利用日').fill(past);
  await response;
  await expect(seat(page, 5)).toHaveText(/予約済み/);
  await expect(seat(page, 8)).toHaveText(/予約不可/);
  await expect(seat(page, 1)).toHaveText(/空席/);
  await expect(page.locator('.seat:enabled')).toHaveCount(0);
  await seat(page, 1).click({ force: true });
  await page
    .getByRole('button', { name: '予約する', exact: true })
    .click({ force: true });
  await page
    .getByRole('button', { name: '予約をキャンセル', exact: true })
    .click({ force: true });
  expect(writes).toHaveLength(0);
  expect(rows()).toBe(before);
});
