import { afterEach, expect, it, vi } from 'vitest';
import { createAuthConfiguration, onSigninCallback } from './authConfig';

const env = () => ({
  VITE_COGNITO_AUTHORITY: 'https://issuer.example/pool',
  VITE_COGNITO_DOMAIN: 'https://login.example',
  VITE_COGNITO_CLIENT_ID: 'public-client',
  VITE_AUTH_REDIRECT_URI: `${location.origin}/auth/callback`,
  VITE_AUTH_LOGOUT_URI: `${location.origin}/login`,
});
afterEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});
it('PKCEを有効にし、トークンはメモリ、認証途中の情報だけsessionStorageへ保持する', async () => {
  const { settings, logoutUrl } = createAuthConfiguration(env());
  expect(settings).toMatchObject({
    response_type: 'code',
    disablePKCE: false,
    automaticSilentRenew: false,
  });
  await settings.userStore!.set('test-user', 'access-token');
  await settings.stateStore!.set('test-state', 'verifier');
  expect(await settings.userStore!.get('test-user')).toBe('access-token');
  expect(sessionStorage.getItem('oidc.test-user')).toBeNull();
  expect(sessionStorage.getItem('oidc.test-state')).toBe('verifier');
  expect(localStorage.length).toBe(0);
  expect(
    await createAuthConfiguration(env()).settings.userStore!.get('test-user'),
  ).toBeFalsy();
  expect(new URL(logoutUrl).searchParams.get('logout_uri')).toBe(
    `${location.origin}/login`,
  );
  expect(new URL(logoutUrl).origin).toBe('https://login.example');
  expect(new URL(logoutUrl).pathname).toBe('/logout');
  expect(new URL(logoutUrl).searchParams.get('client_id')).toBe(
    'public-client',
  );
});
it('必要な設定が欠けた場合は起動時に検出する', () => {
  expect(() => createAuthConfiguration({})).toThrow('認証設定');
});
it('別オリジンのコールバック設定を拒否する', () => {
  expect(() =>
    createAuthConfiguration({
      ...env(),
      VITE_AUTH_REDIRECT_URI: 'https://other.example/auth/callback',
    }),
  ).toThrow('戻り先');
});
it('コールバック後は認証パラメータを除去し予約画面のURLへ置き換える', () => {
  window.history.replaceState(null, '', '/auth/callback?code=test&state=test');
  onSigninCallback();
  expect(location.pathname).toBe('/reservations');
  expect(location.search).toBe('');
});

const keys = Object.keys(env()) as (keyof ReturnType<typeof env>)[];
it.each(
  keys.flatMap((key) =>
    [undefined, '', '   '].map((value) => ({ key, value })),
  ),
)('[AUTH-CFG-02] $key=$value を拒否する', ({ key, value }) => {
  expect(() => createAuthConfiguration({ ...env(), [key]: value })).toThrow(
    key,
  );
});
it.each(keys.filter((key) => key !== 'VITE_COGNITO_CLIENT_ID'))(
  '[AUTH-CFG-05] %sの不正URLを拒否する',
  (key) => {
    expect(() =>
      createAuthConfiguration({ ...env(), [key]: 'not-a-url' }),
    ).toThrow();
  },
);
it.each(['VITE_AUTH_REDIRECT_URI', 'VITE_AUTH_LOGOUT_URI'] as const)(
  '[AUTH-CFG-03] %sのorigin・パス・クエリ・ハッシュを検証する',
  (key) => {
    const path = key === 'VITE_AUTH_REDIRECT_URI' ? '/auth/callback' : '/login';
    for (const url of [
      `https://other.example${path}`,
      `${location.origin}/wrong`,
      `${location.origin}${path}/`,
      `${location.origin}${path}?x=1`,
      `${location.origin}${path}#x`,
    ]) {
      expect(
        () => createAuthConfiguration({ ...env(), [key]: url }),
        url,
      ).toThrow('戻り先');
    }
  },
);
it.each(['VITE_COGNITO_AUTHORITY', 'VITE_COGNITO_DOMAIN'] as const)(
  '[AUTH-CFG-06] %sのHTTPS・開発時ローカルHTTP制約',
  (key) => {
    for (const dev of [true, false]) {
      vi.stubEnv('DEV', dev);
      expect(() =>
        createAuthConfiguration({ ...env(), [key]: 'https://issuer.example' }),
      ).not.toThrow();
      expect(() =>
        createAuthConfiguration({ ...env(), [key]: 'http://issuer.example' }),
      ).toThrow('HTTPS');
      for (const host of ['localhost', '127.0.0.1']) {
        const create = () =>
          createAuthConfiguration({ ...env(), [key]: `http://${host}:18082` });
        if (dev) expect(create).not.toThrow();
        else expect(create).toThrow('HTTPS');
      }
    }
  },
);
