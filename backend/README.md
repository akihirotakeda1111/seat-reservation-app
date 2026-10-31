# Backend

Java 25 / Spring Boot / Spring Data JPAで実装した座席予約API。座席照会・予約・キャンセルを提供し、PostgreSQLに永続化する。DBスキーマはFlywayで管理する。

アプリ全体のローカル起動・停止は[ルートREADME](../README.md#ローカル起動停止)を参照。

## ディレクトリ構成と各ファイルの役割

以下は`src/main/java/com/example/seat_reservation_api/`配下のファイル構成と役割を示す。API契約やテーブル定義の詳細は末尾の関連ドキュメントを参照。

```text
java/com/example/seat_reservation_api/
├── SeatReservationApiApplication.java  # Spring Boot Application
├── component/
│   ├── CurrentUserProvider.java       # 現在のユーザーIDを取得するインターフェース
│   └── DemoCurrentUserProvider.java   # local用の仮ユーザーヘッダー取得・検証
├── config/
│   ├── ClockConfig.java               # 日本時間のClock設定
│   └── ValidationConfig.java          # 日付検証に共通のClockを設定
├── controller/
│   └── ReservationController.java     # 予約Controller
├── dto/
│   ├── request/
│   │   ├── CreateReservationRequest.java  # 予約作成の入力DTO
│   │   └── SeatsRequest.java              # 座席照会のパラメータDTO
│   └── response/
│       ├── ReservationsResponse.java      # 予約作成結果のDTO
│       ├── SeatResponse.java              # 座席管理DTO
│       └── SeatsResponse.java             # 日付と座席一覧のDTO
├── entity/
│   ├── Seat.java                      # 座席マスタのJPAエンティティ
│   └── Reservation.java               # 予約のJPAエンティティ
├── exception/
│   ├── GlobalExceptionHandler.java    # 共通の例外処理
│   ├── InvalidSeatException.java       # 座席マスタ参照の例外
│   └── ReservationNotFoundException.java # 削除対象競合の例外
├── filter/
│   └── SeatCacheControlFilter.java     # 座席照会のキャッシュ無効フィルター
├── repository/
│   ├── SeatRepository.java            # 座席マスタへのDBアクセス
│   ├── ReservationRepository.java     # 予約情報取得、登録、削除
│   └── SeatReservationView.java        # 座席と予約の結合結果
├── service/
│   └── ReservationService.java        # 照会・予約・取消処理と更新トランザクション
└── validation/
    └── Validation.java                # 検証関数
```

## Integration Test

テストクラスは`src/test/java/com/example/seat_reservation_api/integration/`に配置する。

| テストクラス | 検証内容 |
| --- | --- |
| `ReservationApiIntegrationTest` | 実HTTPによるAPI契約・入力検証・業務ルール・DB状態 |
| `ReservationConcurrencyIntegrationTest` | 実HTTPによる同時予約・キャンセル・再予約、異なるリソースの独立した進行、正常終了・DB例外時の応答 |
| `ReservationTransactionIntegrationTest` | ServiceのTransaction境界、flush / commit時の例外、rollback、独立DB接続での競合・待機、DB操作とHTTPの組合せ |

共通の環境設定は`ReservationIntegrationTestConfig`、DBの準備・検証は`ReservationIntegrationTestSupport`、HTTP操作・応答検証は`ReservationHttpTestSupport`にまとめる。Transaction / ConcurrencyのケースIDは各メソッドの`@DisplayName`と[テスト定義](../doc/testing/transaction-concurrency-test-matrix.md)で対応付ける。

Temurin JDK 25.0.5+8（ルートの`.java-version`、`JAVA_HOME`設定）と起動済みDockerを用意し、`backend`ディレクトリで実行する。Mavenは同梱のWrapperを使用する。

結合テスト3クラスを実行する場合（Windows / PowerShell）：

```powershell
.\mvnw.cmd '-Dtest=ReservationApiIntegrationTest,ReservationConcurrencyIntegrationTest,ReservationTransactionIntegrationTest' test
```

macOS / Linux：

```sh
./mvnw '-Dtest=ReservationApiIntegrationTest,ReservationConcurrencyIntegrationTest,ReservationTransactionIntegrationTest' test
```

特定のクラスだけ実行する場合は、`-Dtest`にそのクラス名だけを指定する。

Testcontainersが専用のPostgreSQL 17.11（`postgres:17.11-bookworm`）を起動し、Flywayでテーブルと初期データを作成するため、Composeでの事前起動は不要。初回は依存関係とコンテナイメージのダウンロードが必要。

Transaction / Concurrencyテストはケース間を直列実行し、ケース内のHTTPや独立DB接続を並列化する。自由な競合は反復し、順序を制御するケースではSQLの待機や先行Transaction保留中の後続完了を確認する。反復回数・期待結果・検証対象外の範囲はテスト定義を参照。

Backendの全テストを実行する場合は、Windowsでは`.\mvnw.cmd test`、macOS / Linuxでは`./mvnw test`を使用する。結果は`target/surefire-reports`に出力される。

## Flywayマイグレーションの管理

- SQLは[src/main/resources/db/migration](src/main/resources/db/migration)に配置する。現在の`V1__create_tables.sql`はテーブル作成と30席の初期登録を行う。
- スキーマ変更は`V<番号>__<説明>.sql`の形式で、新しい番号のファイルを追加する（例：`V2__add_reservation_index.sql`）。適用済みSQLは編集・削除せず、修正も新規マイグレーションとして追加する。
- Backend起動時に未適用のマイグレーションが順に実行される。JPAは`ddl-auto: validate`で整合性を検証し、テーブルの作成・変更はFlywayに任せる。
- 変更後はIntegration Testで新規DBへの適用とAPIの動作を検証する。既存DBへの変更は、データを保持した状態での適用も確認する。

ローカルDBの初期化手順は[ルートREADMEの一括停止](../README.md#一括停止)を参照。

## 関連ドキュメント

- [API定義](../doc/system-design/api-definition.md)：リクエスト・レスポンス、業務ルール、エラー仕様
- [データモデル定義](../doc/system-design/data-model.md)：テーブル構造、制約、初期データ
- [API結合テストマトリクス](../doc/testing/api-integration-test-matrix.md)：検証項目
- [Transaction / Concurrencyテスト定義](../doc/testing/transaction-concurrency-test-matrix.md)：実装単位のケースID・実施方法・期待動作・検証結果
