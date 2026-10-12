# Frontend

React / TypeScript / Viteで実装した座席予約画面。日付ごとの座席状況を表示し、仮ユーザーを切り替えて予約・キャンセルを確認できる。

アプリ全体のローカル起動・停止は[ルートREADME](../README.md#ローカル起動停止)を参照。

## 基本操作フロー

1. 「利用日」で予約したい日付を選ぶ。初期表示は日本時間の当日。過去日は閲覧のみ。
2. 「仮ユーザー」でユーザー1／ユーザー2を選ぶ。切替後は、そのユーザーから見た座席状況に更新される。
3. 空席を選び、操作パネルの「予約する」を押す。結果通知と更新後の座席状況を確認する。
4. キャンセルする場合は、自分の「予約済み」の座席を選び、「予約をキャンセル」を押す。他ユーザーの「予約不可」の座席は操作できない。

座席状況は自動更新される。取得に失敗した場合は、表示される「再取得」から更新できる。

## 単体ビルド・テスト

Node.js 24とnpmを用意し、`frontend`ディレクトリで実行する。

```sh
npm ci
npm run build
npm test
```

`build`はTypeScriptのチェックと本番用ビルドを行い、`dist`に出力する。`test`はVitest / Testing Libraryによるテストを1回実行する。いずれもBackend・DBの起動は不要。

ブラウザでの操作検証にはGoogle Chromeを用意し、次を実行する。

```sh
npm run test:e2e
```

APIをモックしたPlaywrightテスト。設定に従ってViteが自動起動するため、個別の起動操作は不要。ポート5173を使用する。

実API・DBとの結合検証は`npm run test:integration`で実行する。追加でJDK 25（`JAVA_HOME`設定）と起動済みDockerが必要。テストが専用PostgreSQL・Backend・Viteを起動するため、ポート18081と5174を空けておく。

## 関連ドキュメント

- [Frontend仕様](../doc/system-design/frontend-specification.md)：画面構成、状態管理、更新・エラー処理の詳細
- [Frontendテストマトリクス](../doc/testing/frontend-test-matrix.md)：検証項目
