interface Props {
  message?: string;
  busy?: boolean;
  onLogin?: () => void;
}

export function LoginPage({ message, busy = false, onLogin }: Props) {
  return (
    <main className="login-page">
      <section className="login-card" aria-labelledby="login-title">
        <h1 id="login-title">座席予約</h1>
        <p>ログインして、利用する日付と座席を選択してください。</p>
        {message && <p role="alert">{message}</p>}
        {busy && <p role="status">ログインを処理しています…</p>}
        <button
          className="button button-primary"
          aria-describedby="login-help"
          type="button"
          disabled={busy || !onLogin}
          onClick={onLogin}
        >
          ログイン
        </button>
        <p id="login-help" className="login-help">
          Cognitoのログイン画面へ移動します。
        </p>
      </section>
    </main>
  );
}
