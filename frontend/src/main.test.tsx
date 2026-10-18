import { act, fireEvent, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import type { Root } from 'react-dom/client';

const roots = vi.hoisted(() => ({ current: undefined as Root | undefined }));
const auth = vi.hoisted(() => ({ provider: vi.fn() }));
vi.mock('react-dom/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-dom/client')>();
  return {
    ...actual,
    createRoot: (...args: Parameters<typeof actual.createRoot>) => {
      roots.current = actual.createRoot(...args);
      return roots.current;
    },
  };
});
vi.mock('react-oidc-context', () => ({ AuthProvider: auth.provider }));
afterEach(() => {
  act(() => roots.current?.unmount());
  roots.current = undefined;
  document.body.innerHTML = '';
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});
it.each(['', 'invalid-url'])(
  '[AUTH-09] 設定不正(%s)はmainでエラー画面を表示し通信しない',
  async (authority) => {
    vi.resetModules();
    vi.stubEnv('VITE_COGNITO_AUTHORITY', authority);
    const fetch = vi.fn();
    vi.stubGlobal('fetch', fetch);
    document.body.innerHTML = '<div id="root"></div>';
    await act(async () => {
      await import('./main');
    });
    expect(screen.getByRole('alert')).toHaveTextContent('ログイン設定');
    const button = screen.getByRole('button', {
      name: /^ログイン$/,
    });
    expect(button).toBeDisabled();
    fireEvent.click(button);
    expect(auth.provider).not.toHaveBeenCalled();
    expect(fetch).not.toHaveBeenCalled();
  },
);
