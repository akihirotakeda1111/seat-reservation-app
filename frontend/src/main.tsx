import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { App } from './App';
import { createAuthConfiguration, onSigninCallback } from './auth/authConfig';
import { LoginPage } from './pages/LoginPage';
import './styles.css';

const root = createRoot(document.getElementById('root')!);
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
