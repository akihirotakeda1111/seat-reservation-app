import {
  act,
  fireEvent,
  render,
  screen,
  cleanup,
} from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';

const state = vi.hoisted(() => ({
  auth: {} as Record<string, unknown>,
  expire: undefined as (() => void) | undefined,
}));
vi.mock('react-oidc-context', () => ({ useAuth: () => state.auth }));
vi.mock('./pages/SeatReservationPage', () => ({
  SeatReservationPage: ({
    onUnauthorized,
    onLogout,
  }: {
    onUnauthorized: () => void;
    onLogout: () => void;
  }) => (
    <>
      <button onClick={onUnauthorized}>予約画面・401を受信</button>
      <button onClick={onLogout}>ログアウト</button>
    </>
  ),
}));
beforeEach(() => {
  state.auth = {
    isLoading: false,
    isAuthenticated: false,
    user: null,
    removeUser: vi.fn().mockResolvedValue(undefined),
    signinRedirect: vi.fn().mockResolvedValue(undefined),
    events: {
      addAccessTokenExpired: (callback: () => void) => {
        state.expire = callback;
        return () => {
          state.expire = undefined;
        };
      },
    },
  };
  window.history.replaceState(null, '', '/reservations');
});
afterEach(cleanup);
it('未認証では予約画面を表示せずログイン開始できる', () => {
  render(<App logoutUrl="https://login.example/logout" />);
  expect(screen.queryByText('予約画面・401を受信')).toBeNull();
  expect(location.pathname).toBe('/login');
  fireEvent.click(screen.getByRole('button', { name: /^ログイン$/ }));
  expect(state.auth.signinRedirect).toHaveBeenCalledOnce();
});
it.each([{ groups: [] }, { groups: ['ADMINS'] }])(
  'Group=%jでもログイン後は予約画面',
  ({ groups }) => {
    state.auth.isAuthenticated = true;
    state.auth.user = {
      access_token: 'access-token',
      expired: false,
      profile: { sub: 'a', 'cognito:groups': groups },
    };
    render(<App logoutUrl="https://login.example/logout" />);
    expect(screen.getByText('予約画面・401を受信')).toBeInTheDocument();
    expect(location.pathname).toBe('/reservations');
  },
);
it.each(['401', 'expiry'])('%sで画面を破棄して再ログインを案内', (reason) => {
  state.auth.isAuthenticated = true;
  state.auth.user = {
    access_token: 'access-token',
    expired: false,
    profile: { sub: 'a' },
  };
  render(<App logoutUrl="https://login.example/logout" />);
  if (reason === '401')
    fireEvent.click(screen.getByText('予約画面・401を受信'));
  else act(() => state.expire!());
  expect(screen.queryByText('予約画面・401を受信')).toBeNull();
  expect(screen.getByRole('alert')).toHaveTextContent('有効期限');
  expect(state.auth.removeUser).toHaveBeenCalled();
});
it.each(['isLoading', 'activeNavigator'])(
  '[AUTH-04] %sで認証処理中は予約画面を表示しない',
  (property) => {
    state.auth[property] = property === 'isLoading' ? true : 'signinRedirect';
    render(<App logoutUrl="https://login.example/logout" />);
    expect(screen.getByRole('status')).toHaveTextContent('ログインを処理');
    expect(screen.getByRole('button')).toBeDisabled();
  },
);
it('コールバック失敗は詳細情報を公開せず再試行可能', () => {
  state.auth.error = new Error('secret-code');
  window.history.replaceState(
    null,
    '',
    '/auth/callback?code=secret-code&state=invalid',
  );
  render(<App logoutUrl="https://login.example/logout" />);
  expect(screen.getByRole('alert')).toHaveTextContent('ログインに失敗');
  expect(screen.queryByText('secret-code')).toBeNull();
  expect(location.search).toBe('');
  expect(screen.getByRole('button')).toBeEnabled();
  fireEvent.click(screen.getByRole('button'));
  expect(state.auth.signinRedirect).toHaveBeenCalledOnce();
});
it('ログイン開始失敗後に再試行できる', async () => {
  state.auth.signinRedirect = vi
    .fn()
    .mockRejectedValueOnce(new Error('network'))
    .mockResolvedValue(undefined);
  render(<App logoutUrl="https://login.example/logout" />);
  await act(async () => fireEvent.click(screen.getByRole('button')));
  expect(screen.getByRole('alert')).toHaveTextContent('開始できません');
  expect(screen.getByRole('button')).toBeEnabled();
  await act(async () => fireEvent.click(screen.getByRole('button')));
  expect(state.auth.signinRedirect).toHaveBeenCalledTimes(2);
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByRole('button')).toBeDisabled();
});

it.each([
  { name: '期限切れ', expired: true, access_token: 'old-token' },
  { name: 'トークンなし', expired: false, access_token: '' },
])(
  '[AUTH-07] 初期状態が$nameなら予約画面を表示しない',
  ({ expired, access_token }) => {
    state.auth.isAuthenticated = true;
    state.auth.user = { expired, access_token, profile: { sub: 'a' } };
    render(<App logoutUrl="https://login.example/logout" />);
    expect(screen.queryByText('予約画面・401を受信')).toBeNull();
    expect(location.pathname).toBe('/login');
    expect(screen.getByRole('button', { name: /^ログイン$/ })).toBeEnabled();
    if (expired) expect(state.auth.removeUser).toHaveBeenCalled();
  },
);

it('[AUTH-08] ログアウト失敗でも予約画面を破棄しログインを再試行できる', async () => {
  state.auth.isAuthenticated = true;
  state.auth.user = {
    access_token: 'token',
    expired: false,
    profile: { sub: 'a' },
  };
  state.auth.removeUser = vi
    .fn()
    .mockRejectedValue(new Error('storage failure'));
  render(<App logoutUrl="https://login.example/logout" />);
  await act(async () =>
    fireEvent.click(screen.getByRole('button', { name: 'ログアウト' })),
  );
  expect(state.auth.removeUser).toHaveBeenCalledOnce();
  expect(screen.queryByText('予約画面・401を受信')).toBeNull();
  expect(screen.getByRole('alert')).toHaveTextContent(
    'ログアウトを完了できません',
  );
  expect(location.pathname).toBe('/login');
  fireEvent.click(screen.getByRole('button', { name: /^ログイン$/ }));
  expect(state.auth.signinRedirect).toHaveBeenCalledOnce();
});
