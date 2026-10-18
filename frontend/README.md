# Frontend

React / TypeScript / Viteによるログイン画面・座席予約画面・管理者予約一覧画面。

## 認証設定

CognitoでクライアントシークレットのないSPA用App ClientとManaged Loginのドメインを用意する。Authorization Code Grant、PKCE、`openid`スコープを使用する。一般ユーザー・管理者ともログイン後は`/reservations`へ遷移する。

Cognitoの許可URLに以下を登録する。

- Callback URL：`http://localhost:5173/auth/callback`
- Sign-out URL：`http://localhost:5173/login`

Docker Composeではルートの`.env.example`を`.env`へコピーして実際のUser Pool・App Client・ドメインを設定する。起動は[ルートREADME](../README.md#ローカル起動停止)を参照。BackendとFrontendは同じIssuer・Client IDを使用する。

Frontendだけ起動する場合は`frontend/.env.example`を`frontend/.env.local`へコピーして設定し、`npm ci`、`npm run dev`を実行する。Backendは別途起動する。APIはViteの`/api`プロキシを経由する。

| 設定                   | 内容                                                       |
| ---------------------- | ---------------------------------------------------------- |
| VITE_COGNITO_AUTHORITY | User PoolのIssuer URL（Managed Loginのドメインとは異なる） |
| VITE_COGNITO_CLIENT_ID | シークレットなしApp Client ID                              |
| VITE_COGNITO_DOMAIN    | Managed LoginのHTTPSドメイン                               |
| VITE_AUTH_REDIRECT_URI | このサイトの`/auth/callback`の絶対URL                      |
| VITE_AUTH_LOGOUT_URI   | このサイトの`/login`の絶対URL                              |

ポートや公開URLを変更する場合はCognitoの許可URLと上記URLを揃える。`VITE_`変数は公開設定なので秘密情報を含めない。本番ビルド時に取り込まれるため、変更時は再ビルドする。本番配信では`/login`・`/auth/callback`・`/reservations`・`/admin/reservations`をSPAのindex.htmlに解決し、`/api`はBackendへ転送する。

ブラウザで開くURL、戻り先の設定、Cognitoの許可URLは一致させる。`localhost`と`127.0.0.1`は混在させない。ログイン時の`redirect_mismatch`やログアウト時のInvalid requestでは、App Clientと許可URLの設定を確認する。

## 基本操作フロー

### 共通：ログイン・ログアウト

「ログイン」からCognito Managed Loginへ移動し、Cognito側で認証情報を入力する。ログイン後は権限にかかわらず一般予約画面へ進む。「ログアウト」は両画面から実行でき、Cognitoを経由してログイン画面へ戻る。

トークンはメモリに保持する。再読み込み・有効期限切れ・APIの401では再ログインを案内し、403では権限エラーを表示する。

### 一般：予約・キャンセル

1. 予約画面で「利用日」を選ぶ。初期表示は日本時間の当日。
2. 空席を選び「予約する」を押す。
3. 自分の予約済み座席を選び「予約をキャンセル」で取り消す。

座席状況は前回の取得完了から5秒後に更新する。一般画面では管理者も他人の予約は取り消せない。過去日は閲覧のみ。

### 管理者：予約一覧・キャンセル

Cognitoの`ADMINS`グループに所属するユーザーに「予約管理へ」を表示する。グループ変更後は再ログインする。

1. 「予約管理へ」で管理者画面を開く。
2. 利用日を指定し「予約一覧を取得」を押す。座席番号・予約者ID・件数・最終取得時刻を表示する。
3. 対象行の「キャンセル」で内容を確認し、「予約をキャンセル」で確定する。「戻る」で中止できる。
4. 操作結果と再取得した一覧を確認する。「予約画面へ」で一般画面へ戻る。

管理者一覧は手動取得。他人の予約もキャンセルできるが、過去日は閲覧のみ。取得失敗時は「再取得」で最新情報を確認する。処理中の追加操作と、失敗した取消の自動再送は行わない。

## ビルド・テスト

Node.js 24とnpmを用意し、`frontend`で実行する。

```sh
npm ci
npm run build
npm test
npm run test:e2e
npm run test:integration
```

`build`は型検証と本番ビルド、`test`はVitest / Testing Library。ブラウザテストにはGoogle Chromeが必要。

E2EはAPIをモックし、Vite（5174）とテスト専用OIDCサーバー（18082）を自動起動する。Authorization Code + PKCEによる認証を行い、テストユーザーを選択する。

実API結合テストはJDK 25（JAVA_HOME）と起動済みDockerが必要。専用PostgreSQL、Backend（18081）、Vite（5174）、同OIDCサーバー（18082）を起動し、署名付きJWTをBackendが検証する。E2Eと結合テストは同時実行しない。テスト用Issuerはテスト設定だけで利用し、実Cognitoへの接続・設定は別途手動確認する。

認証・一般予約・管理者機能の単体／ブラウザ／実API結合テストを用意する。個別ケースはテストマトリクスを参照。実Cognitoの許可URL・ログインセッション・メール配信は別途確認する。

E2Eのポートは5174固定とし、通常開発の5173と分離する。実API結合テストと5174・18082を共用するため、同時実行しない。

## 関連ドキュメント

- [Frontend仕様](../doc/system-design/frontend-specification.md)：画面構成、状態管理、更新・エラー処理の詳細
- [Frontendテストマトリクス](../doc/testing/frontend-test-matrix.md)：検証項目
