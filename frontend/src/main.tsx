import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { App } from './App';
import { createAuthConfiguration, onSigninCallback } from './auth/authConfig';
import { LoginPage } from './pages/LoginPage';
import './styles.css';

const root = createRoot(document.getElementById('root')!);
async function start() {
  if (import.meta.env.VITE_DEMO_AUTH === 'true') {
    const userId = import.meta.env.VITE_DEMO_USER_ID?.trim();
    const role = import.meta.env.VITE_DEMO_ROLE;
    if (!import.meta.env.DEV || !userId || !['USER', 'ADMIN'].includes(role)) {
      root.render(
        <LoginPage message="固定ユーザーモードは開発環境で有効なユーザー・権限を指定してください。" />,
      );
      return;
    }
    const { DemoApp } = await import('./DemoApp');
    root.render(
      <StrictMode>
        <DemoApp userId={userId} role={role as 'USER' | 'ADMIN'} />
      </StrictMode>,
    );
    return;
  }
  try {
    const config = createAuthConfiguration(import.meta.env);
    root.render(
      <StrictMode>
        <AuthProvider {...config.settings} onSigninCallback={onSigninCallback}>
          <App logoutUrl={config.logoutUrl} />
        </AuthProvider>
      </StrictMode>,
    );
  } catch {
    root.render(<LoginPage message="ログイン設定が不足しています。" />);
  }
}
void start();
