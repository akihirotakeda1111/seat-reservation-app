# API定義

## 1. 概要

Phase 3では、JWT認証を必須とする一般向けAPIと、ADMIN権限を必要とする管理者向けAPIを提供する。[データモデル定義](data-model.md)の`seats`と`reservations`を使用し、Entity・DBスキーマは変更しない。

| Method | Endpoint                 | 内容           |
| ------ | ------------------------ | -------------- |
| GET    | `/api/seats?date={date}` | 座席の状態取得 |
| POST   | `/api/reservations`      | 座席予約       |
| DELETE | `/api/reservations/{id}` | 予約キャンセル |
| GET | `/api/admin/reservations?date={date}` | 指定日の全予約取得（ADMIN） |
| DELETE | `/api/admin/reservations/{id}` | 任意ユーザーの予約キャンセル（ADMIN） |

## 2. 共通仕様

- リクエスト本文と成功レスポンス本文の`Content-Type`は`application/json`とする。エラーレスポンスはProblemDetailで統一し、`Content-Type`は`application/problem+json`とする。
- 日付は`yyyy-MM-dd`形式とする。予約日が当日以降であるかは、日本時間（`Asia/Tokyo`）を基準に判定する。
- 予約IDはUUID形式の文字列とする。
- localを含め、`Authorization: Bearer <Access Token>`によるJWT認証を必須とする。操作ユーザーは検証済みJWTの`sub`で識別する。
- `X-Demo-User`はAPIの認証・所有者判定に使用しない。このヘッダーだけを送信した場合は401となる。既存Controllerテスト用のDemo実装は本番アプリケーションには含まれない。
- Cognitoの署名・issuer・有効期間、`token_use=access`、許可した`client_id`、空でない`sub`（128文字以内）、`exp`の存在を検証する。ID Tokenは受け付けない。
- `cognito:groups`が文字列の配列で`ADMINS`を含む場合、`ROLE_ADMIN`へ変換する。Group欠落・不正形式では管理者権限を付与しない。
- `/api/admin/**`はADMINのみ、それ以外の一般向けAPIは認証済みユーザーに許可する。管理者も一般向けAPIを利用できるが、一般向けキャンセルでは所有者判定を免除しない。
- APIはステートレスとし、Cookie／セッションによる認証は行わない。
- リクエスト本文やクエリから任意の`user_id`を指定する仕様は設けない。

## 3. 座席照会

### リクエスト

```http
GET /api/seats?date=2026-10-10
Authorization: Bearer <Access Token>
```

| パラメータ | 場所   | 型             | 必須 | 内容                               |
| ---------- | ------ | -------------- | ---- | ---------------------------------- |
| date       | クエリ | string（date） | 必須 | 照会対象日。実在する日付を指定する |

リクエスト本文は送信しない。

### 成功レスポンス

`200 OK`を返す。以下は3席分を抜粋した例であり、実際にはPhase 1の座席マスタに登録した全席を必ず返す。

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store
```

操作ユーザーごとの予約状態を含むため、GET `/api/seats`のレスポンスには、エラー時も含めて`Cache-Control: no-store`を付与する。

```json
{
  "date": "2026-10-10",
  "seats": [
    {
      "seatNumber": 1,
      "status": "AVAILABLE"
    },
    {
      "seatNumber": 2,
      "status": "MINE",
      "reservationId": "550e8400-e29b-41d4-a716-446655440000"
    },
    {
      "seatNumber": 3,
      "status": "OCCUPIED"
    }
  ]
}
```

| フィールド            | 型             | 内容                                      |
| --------------------- | -------------- | ----------------------------------------- |
| date                  | string（date） | 照会対象日                                |
| seats                 | array          | 座席番号の昇順で並べた全座席の状態        |
| seats[].seatNumber    | integer        | 座席番号                                  |
| seats[].status        | string         | `AVAILABLE`、`MINE`、`OCCUPIED`のいずれか |
| seats[].reservationId | string（UUID） | `MINE`の場合のみ返すキャンセル用の予約ID  |

| status    | 判定条件                                         |
| --------- | ------------------------------------------------ |
| AVAILABLE | 指定日・指定座席の予約が存在しない               |
| MINE      | 指定日・指定座席の予約が操作ユーザーのものである |
| OCCUPIED  | 指定日・指定座席の予約が他ユーザーのものである   |

- `seats`を基準に指定日の予約を突き合わせ、予約がない座席も返す。
- `AVAILABLE`と`OCCUPIED`では`reservationId`フィールドを省略する。他ユーザーの予約IDやユーザー情報は公開しない。
- 初期構成は30席とする。将来の座席数変更時もマスタを基準とし、API処理で座席番号の上限を30に固定しない。

### ステータスコード

| HTTPステータス            | 条件                                                               |
| ------------------------- | ------------------------------------------------------------------ |
| 200 OK                    | 照会成功                                                           |
| 400 Bad Request           | 日付の未指定・形式不正・存在しない日付、または共通仕様の入力エラー |
| 401 Unauthorized | 未認証・不正または期限切れのJWT |
| 500 Internal Server Error | システムエラー                                                     |

## 4. 座席予約

### リクエスト

```http
POST /api/reservations
Content-Type: application/json
Authorization: Bearer <Access Token>
```

```json
{
  "date": "2026-10-10",
  "seatNumber": 1
}
```

| フィールド | 型             | 必須 | 入力条件                           |
| ---------- | -------------- | ---- | ---------------------------------- |
| date       | string（date） | 必須 | 実在する日付かつ日本時間で当日以降 |
| seatNumber | integer        | 必須 | 座席マスタに存在する座席番号       |

### 処理ルール

- マスタに存在する座席を予約可能な座席とする。
- 操作ユーザーを予約の`user_id`として登録する。
- 予約IDを生成し、`reservations`に1レコードをINSERTする。
- 同一ユーザーが同じ日に複数席を予約することは許可する。
- 同一日付・同一座席への予約は、操作ユーザー自身の既存予約も含めて`409 Conflict`とする。
- 予約競合は事前の空席確認だけに依存せず、最終的に予約重複UNIQUE制約`uq_reservations_date_seat`（`reservation_date, seat_number`）で防止する。
- DB制約違反から`409 Conflict`への変換対象は、制約名を確認して`uq_reservations_date_seat`への違反と特定できた場合に限定する。`DataIntegrityViolationException`などの例外型だけで一律に409へ変換しない。
- 主キー・外部キー・NOT NULL・CHECK・その他のUNIQUE制約への違反や、制約を特定できないエラーは409へ変換せず、想定外のDBエラーとして`500 Internal Server Error`を返す。入力検証で検出した不正な日付・座席番号などは従来どおり`400 Bad Request`とする。
- 外部キー`reservations.seat_number → seats.seat_number`によって座席の存在を保証する。
- 予約登録はトランザクション内で行い、コミット成功後に成功レスポンスを返す。

### 成功レスポンス

`201 Created`を返す。成功時の本文は以下とする。

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "date": "2026-10-10",
  "seatNumber": 1
}
```

| フィールド | 型             | 内容                                              |
| ---------- | -------------- | ------------------------------------------------- |
| id         | string（UUID） | 作成した予約のID。キャンセルAPIの`{id}`に指定する |
| date       | string（date） | 予約対象日                                        |
| seatNumber | integer        | 予約した座席番号                                  |

### ステータスコード

| HTTPステータス            | 条件                                                                                                               |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 201 Created               | 予約成功                                                                                                           |
| 400 Bad Request           | 必須項目の未指定・null、JSONや型の不正、不正な日付・過去日、マスタに存在しない座席番号、または共通仕様の入力エラー |
| 409 Conflict              | 同一日付・同一座席が予約済み                                                                                       |
| 401 Unauthorized | 未認証・不正または期限切れのJWT |
| 500 Internal Server Error | システムエラー                                                                                                     |

## 5. 予約キャンセル

### リクエスト

```http
DELETE /api/reservations/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <Access Token>
```

| パラメータ | 場所 | 型             | 必須 | 内容                   |
| ---------- | ---- | -------------- | ---- | ---------------------- |
| id         | パス | string（UUID） | 必須 | キャンセル対象の予約ID |

リクエスト本文は送信しない。

### 処理ルール

- 対象の予約が存在しない場合は`404 Not Found`を返す。
- 予約の`user_id`と操作ユーザーが一致しない場合は、削除せず`403 Forbidden`を返す。
- 操作ユーザー自身の予約のみ、トランザクション内でDELETEする。
- 予約履歴・キャンセル履歴は保持しない。
- 削除済みの予約IDに再度リクエストした場合は`404 Not Found`を返す。
- 予約対象日が日本時間（Asia/Tokyo）で当日以降の場合のみ、キャンセルを許可する。
- 過去日の予約に対するキャンセルは、レコードを削除せず`400 Bad Request`を返す。
- 判定順序は、予約の存在確認→所有者確認→予約日の検証→削除処理とする。

### 成功レスポンス

コミット成功後に`204 No Content`を返す。レスポンス本文は返さない。

### ステータスコード

| HTTPステータス            | 条件                                             |
| ------------------------- | ------------------------------------------------ |
| 204 No Content            | キャンセル成功                                   |
| 400 Bad Request           | 過去日の予約、予約IDのUUID形式不正、または共通仕様の入力エラー |
| 403 Forbidden             | 他ユーザーの予約                                 |
| 404 Not Found             | 予約IDが存在しない                               |
| 401 Unauthorized | 未認証・不正または期限切れのJWT |
| 500 Internal Server Error | システムエラー                                   |

Controllerは`cancelOwnReservation(id, sub)`を呼ぶ。予約取得・所有者確認・過去日確認・削除はServiceの同一トランザクション内で行う。管理者も他人の予約には403となる。確認後の競合でDELETE件数が0件となった場合も404を返す。

## 6. 管理者用予約一覧

```http
GET /api/admin/reservations?date=2026-10-16
Authorization: Bearer <Admin Access Token>
```

`AdminReservationController`を既存の`controller`パッケージに配置する。`date`は必須の実在日付（`yyyy-MM-dd`）であり、過去日も照会できる。指定日の全ユーザーの予約を返し、別日の予約は含めない。日付をまたぐ全件検索・ページングは設けない。

成功時は200と次のJSONを返す。予約がない場合は`reservations: []`とする。

```json
{
  "date": "2026-10-16",
  "reservations": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "seatNumber": 1,
      "userId": "11111111-1111-4111-8111-111111111111"
    }
  ]
}
```

| フィールド | 型 | 内容 |
| --- | --- | --- |
| date | string（date） | 照会対象日 |
| reservations | array | 指定日に存在する予約。空席は含めない |
| reservations[].id | string（UUID） | 管理者用キャンセルに使用する予約ID |
| reservations[].seatNumber | integer | 座席番号 |
| reservations[].userId | string | 所有者のJWTの`sub` |

既存の`ReservationService.getAllReservations(date)`と`ReservationRepository`を共用する。レスポンスには専用DTOを使い、ユーザー名やメールアドレスは返さない。

| HTTPステータス | 条件 |
| --- | --- |
| 200 OK | 照会成功（0件を含む） |
| 400 Bad Request | dateの欠落・形式不正・存在しない日付 |
| 401 Unauthorized | 未認証・不正または期限切れのJWT |
| 403 Forbidden | 認証済みだがADMIN権限なし |
| 500 Internal Server Error | システムエラー |

## 7. 管理者用予約キャンセル

```http
DELETE /api/admin/reservations/550e8400-e29b-41d4-a716-446655440000
Authorization: Bearer <Admin Access Token>
```

`id`は必須のUUID形式のパスパラメータ。本文は送信しない。

- `AdminReservationController`から`ReservationService.cancelReservationAsAdmin(id)`を呼び出す。
- 所有者判定を省略し、予約存在確認→過去日確認→DELETEを同一トランザクション内で行う。
- 過去日禁止は管理者にも適用する。自身・他人いずれの予約も同じ条件とする。
- 一般用と管理者用のキャンセルは共通privateメソッドを利用し、過去日判定・DELETE件数判定を共有する。
- `AdminReservationService`は作成しない。管理者用Serviceメソッド2つは`@PreAuthorize("hasRole('ADMIN')")`でも保護し、`@EnableMethodSecurity`で有効化する。

| HTTPステータス | 条件 |
| --- | --- |
| 204 No Content | commit成功。本文なし |
| 400 Bad Request | UUID形式不正、過去日の予約 |
| 401 Unauthorized | 未認証・不正または期限切れのJWT |
| 403 Forbidden | 認証済みだがADMIN権限なし。予約の有無によらず拒否 |
| 404 Not Found | 予約なし、または競合によりDELETE件数0 |
| 500 Internal Server Error | システムエラー |

## 8. エラーレスポンス

本定義のすべてのエラーレスポンスは、Springの`ProblemDetail`を用い、[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)に準拠した形式で返す。入力検証エラー、JSONの読み取りエラー、業務エラー、システムエラーを同じ形式に統一する。

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
```

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "指定された日付・座席は予約済みです。",
  "instance": "/api/reservations"
}
```

| フィールド | 型                | 内容                                                                                  |
| ---------- | ----------------- | ------------------------------------------------------------------------------------- |
| type       | string（URI参照） | 省略可能。省略時は`about:blank`として扱う。明示する場合も`about:blank`とする |
| title      | string            | HTTPステータスに対応する概要                                                          |
| status     | integer           | 実際のHTTPステータスコードと一致させる                                                |
| detail     | string            | エラー内容の説明。従来の`message`に代えて使用する                                     |
| instance   | string（URI参照） | エラーが発生したリクエストのパス                                                      |

本APIでは`title`、`status`、`detail`、`instance`を必ず返す。`type`の省略を許容し、上記のレスポンス例でも省略している。クライアントは`type`が省略されている場合、RFC 9457に従って`about:blank`として扱う。

| HTTPステータス            | title                 | detailの例                             |
| ------------------------- | --------------------- | -------------------------------------- |
| 400 Bad Request           | Bad Request           | リクエストの内容が不正です。           |
| 401 Unauthorized | Unauthorized | 認証が必要です。 |
| 403 Forbidden | Forbidden | 操作権限がありません。 |
| 403 Forbidden             | Forbidden             | 指定された予約はキャンセルできません。 |
| 404 Not Found             | Not Found             | 指定された予約が見つかりません。       |
| 409 Conflict              | Conflict              | 指定された日付・座席は予約済みです。   |
| 400 Bad Request           | Bad Request           | 過去の予約はキャンセルできません。     |
| 500 Internal Server Error | Internal Server Error | システムエラーが発生しました。         |

エラー本文に他ユーザーのユーザー情報や、リクエストで指定されたもの以外の予約IDを含めない。`instance`にはクエリ文字列を含めない。システムエラーの内部情報やSQL、制約名、スタックトレースはレスポンスに含めない。

Security Filterの認証・認可失敗は`SecurityErrorConfig`の`AuthenticationEntryPoint`／`AccessDeniedHandler`が本文を生成する。標準Bearerハンドラーのステータスと`WWW-Authenticate`を保持する。401ではBearer challengeを確認する。Serviceのメソッド認可による`AccessDeniedException`は`GlobalExceptionHandler`が403へ変換し、汎用500として扱わない。

未認証や管理者権限不足は業務処理より前に拒否する。入力検証の400・業務上の403／404を確認する際は、対象APIにアクセス可能な有効なAccess Tokenを送信する。Bearerヘッダーの重複など、認証要求自体の形式不正は標準Bearerハンドラーの400（`invalid_request`）になり得るため、不正JWTによる401と区別する。
