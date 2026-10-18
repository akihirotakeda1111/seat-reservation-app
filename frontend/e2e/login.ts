import { expect, type Page } from '@playwright/test';

export async function login(page: Page, user = 'demo-user-1') {
  await page.goto('/login');
  await page.getByRole('button', { name: 'ログイン', exact: true }).click();
  await page.getByLabel('テストユーザー').selectOption(user);
  await page.getByRole('button', { name: '認証する', exact: true }).click();
  await expect(page).toHaveURL(/\/reservations$/);
}

export async function switchAccount(page: Page, user: string) {
  await page.getByRole('button', { name: 'ログアウト', exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await login(page, user);
}
