import { useEffect, useState } from 'react';
import { SeatReservationPage } from './pages/SeatReservationPage';
import { AdminReservationPage } from './pages/AdminReservationPage';

export function DemoApp({
  userId,
  role,
}: {
  userId: string;
  role: 'USER' | 'ADMIN';
}) {
  const [adminView, setAdminView] = useState(false);
  const [stopped, setStopped] = useState(false);
  const [error, setError] = useState(false);
  useEffect(() => {
    window.history.replaceState(
      null,
      '',
      stopped ? '/login' : adminView ? '/admin/reservations' : '/reservations',
    );
  }, [adminView, stopped]);
  const props = {
    accessToken: 'local-demo',
    onUnauthorized: () => {
      setError(true);
      setStopped(true);
    },
    onLogout: () => {
      setStopped(true);
      setAdminView(false);
    },
  };
  return (
    <>
      <div className="message" role="note">
        開発用・認証省略：{userId}（{role}）
      </div>
      {stopped ? (
        <main>
          <p role={error ? 'alert' : undefined}>
            {error
              ? '固定ユーザー設定を確認してください。'
              : '固定ユーザーでの操作を終了しました。'}
          </p>
          <button
            className="button button-primary"
            onClick={() => {
              setError(false);
              setAdminView(false);
              setStopped(false);
            }}
          >
            操作を再開
          </button>
        </main>
      ) : adminView && role === 'ADMIN' ? (
        <AdminReservationPage
          {...props}
          onReservations={() => setAdminView(false)}
        />
      ) : (
        <SeatReservationPage
          {...props}
          onAdmin={role === 'ADMIN' ? () => setAdminView(true) : undefined}
        />
      )}
    </>
  );
}
