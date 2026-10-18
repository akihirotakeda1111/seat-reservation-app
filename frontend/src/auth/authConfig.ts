import {
  InMemoryWebStorage,
  WebStorageStateStore,
  type UserManagerSettings,
} from 'oidc-client-ts';

export interface AuthConfiguration {
  settings: UserManagerSettings;
  logoutUrl: string;
}

export function createAuthConfiguration(
  env: Record<string, string | undefined>,
): AuthConfiguration {
  const required = (key: string) => {
    const value = env[key]?.trim();
    if (!value) throw new Error(`認証設定 ${key} が必要です。`);
    return value;
  };
  const authority = new URL(required('VITE_COGNITO_AUTHORITY'));
  const domain = new URL(required('VITE_COGNITO_DOMAIN'));
  const clientId = required('VITE_COGNITO_CLIENT_ID');
  const redirect = new URL(required('VITE_AUTH_REDIRECT_URI'));
  const logout = new URL(required('VITE_AUTH_LOGOUT_URI'));
  const secureOrLocalDev = (url: URL) =>
    url.protocol === 'https:' ||
    (import.meta.env.DEV &&
      url.protocol === 'http:' &&
      ['localhost', '127.0.0.1'].includes(url.hostname));
  if (!secureOrLocalDev(authority) || !secureOrLocalDev(domain)) {
    throw new Error('CognitoのURLにはHTTPSを指定してください。');
  }
  if (
    redirect.origin !== window.location.origin ||
    logout.origin !== window.location.origin ||
    redirect.pathname !== '/auth/callback' ||
    logout.pathname !== '/login' ||
    redirect.search ||
    redirect.hash ||
    logout.search ||
    logout.hash
  ) {
    throw new Error(
      '認証の戻り先を、このサイトの/auth/callbackと/loginに設定してください。',
    );
  }
  const logoutUrl = new URL('/logout', domain);
  logoutUrl.searchParams.set('client_id', clientId);
  logoutUrl.searchParams.set('logout_uri', logout.href);
  return {
    settings: {
      authority: authority.href.replace(/\/$/, ''),
      client_id: clientId,
      redirect_uri: redirect.href,
      response_type: 'code',
      scope: 'openid',
      disablePKCE: false,
      automaticSilentRenew: false,
      monitorSession: false,
      loadUserInfo: false,
      userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
      stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    },
    logoutUrl: logoutUrl.href,
  };
}

export function onSigninCallback() {
  window.history.replaceState(null, '', '/reservations');
}
