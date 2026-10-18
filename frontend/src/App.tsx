import { useCallback, useEffect, useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { LoginPage } from './pages/LoginPage';
import { SeatReservationPage } from './pages/SeatReservationPage';

export function App({ logoutUrl }: { logoutUrl: string }) {
  const auth = useAuth();
  const [message, setMessage] = useState<string>();
  const [blocked, setBlocked] = useState(false);
  const [redirecting, setRedirecting] = useState(false);
  const { removeUser, events } = auth;
  const accessToken = auth.user?.access_token;
  const authenticated =
    auth.isAuthenticated && !auth.user?.expired && !!accessToken && !blocked;

  const expire = useCallback(() => {
    setBlocked(true);
    setMessage('ログインの有効期限が切れました。');
    void removeUser().catch(() => {});
  }, [removeUser]);

  useEffect(() => events.addAccessTokenExpired(expire), [events, expire]);
  useEffect(() => {
    if (auth.user?.expired) expire();
  }, [auth.user, expire]);
  useEffect(() => {
    if (!auth.isLoading && !auth.activeNavigator) {
      window.history.replaceState(
        null,
        '',
        authenticated ? '/reservations' : '/login',
      );
    }
  }, [auth.isLoading, auth.activeNavigator, authenticated]);

  async function login() {
    if (redirecting) return;
    setRedirecting(true);
    setMessage(undefined);
    window.history.replaceState(null, '', '/login');
    try {
      await auth.signinRedirect();
    } catch {
      setMessage('ログインを開始できませんでした。');
      setRedirecting(false);
    }
  }

  async function logout() {
    setBlocked(true);
    setRedirecting(true);
    try {
      await removeUser();
      window.location.assign(logoutUrl);
    } catch {
      setMessage('ログアウトを完了できませんでした。');
      setRedirecting(false);
    }
  }

  if (auth.isLoading || auth.activeNavigator || redirecting)
    return <LoginPage busy />;
  if (!authenticated)
    return (
      <LoginPage
        onLogin={() => void login()}
        message={
          message ?? (auth.error ? 'ログインに失敗しました。' : undefined)
        }
      />
    );

  return (
    <SeatReservationPage
      key={auth.user!.profile.sub}
      accessToken={accessToken!}
      onUnauthorized={expire}
      onLogout={() => void logout()}
    />
  );
}
