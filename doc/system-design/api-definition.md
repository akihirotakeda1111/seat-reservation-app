# API定義

## 1. 概要

Phase 1では、座席照会・座席予約・予約キャンセルの3つのREST APIを提供する。[データモデル定義](data-model.md)の`seats`と`reservations`を使用する。

| Method | Endpoint | 内容 |
| --- | --- | --- |
| GET | `/api/seats?date={date}` | 座席の状態取得 |
| POST | `/api/reservations` | 座席予約 |
| DELETE | `/api/reservations/{id}` | 予約キャンセル |

## 2. 共通仕様

- リクエスト本文と成功レスポンス本文の`Content-Type`は`application/json`とする。エラーレスポンスはProblemDetailで統一し、`Content-Type`は`application/problem+json`とする。
- 日付は`yyyy-MM-dd`形式とする。予約日が当日以降であるかは、日本時間（`Asia/Tokyo`）を基準に判定する。
- 予約IDはUUID形式の文字列とする。
- Phase 1では、ローカルプロファイルでのみ`X-Demo-User`ヘッダーから操作ユーザーを取得する。値は`demo-user-1`または`demo-user-2`とし、未指定・不正な値は`400 Bad Request`とする。
- `X-Demo-User`は開発専用の仮ユーザー切替であり、認証機能ではない。Phase 3ではJWTの`sub`からユーザー識別子を取得する方式へ置き換える。
- リクエスト本文やクエリから任意の`user_id`を指定する仕様は設けない。

## 3. 座席照会

### リクエスト

```http
GET /api/seats?date=2026-10-10
X-Demo-User: demo-user-1
```

| パラメータ | 場所 | 型 | 必須 | 内容 |
| --- | --- | --- | --- | --- |
| date | クエリ | string（date） | 必須 | 照会対象日。実在する日付を指定する |

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

| フィールド | 型 | 内容 |
| --- | --- | --- |
| date | string（date） | 照会対象日 |
| seats | array | 座席番号の昇順で並べた全座席の状態 |
| seats[].seatNumber | integer | 座席番号 |
| seats[].status | string | `AVAILABLE`、`MINE`、`OCCUPIED`のいずれか |
| seats[].reservationId | string（UUID） | `MINE`の場合のみ返すキャンセル用の予約ID |

| status | 判定条件 |
| --- | --- |
| AVAILABLE | 指定日・指定座席の予約が存在しない |
| MINE | 指定日・指定座席の予約が操作ユーザーのものである |
| OCCUPIED | 指定日・指定座席の予約が他ユーザーのものである |

- `seats`を基準に指定日の予約を突き合わせ、予約がない座席も返す。
- `AVAILABLE`と`OCCUPIED`では`reservationId`フィールドを省略する。他ユーザーの予約IDやユーザー情報は公開しない。
- 初期構成は30席とする。将来の座席数変更時もマスタを基準とし、API処理で座席番号の上限を30に固定しない。

### ステータスコード

| HTTPステータス | 条件 |
| --- | --- |
| 200 OK | 照会成功 |
| 400 Bad Request | 日付の未指定・形式不正・存在しない日付、または共通仕様の入力エラー |
| 500 Internal Server Error | システムエラー |

## 4. 座席予約

### リクエスト

```http
POST /api/reservations
Content-Type: application/json
X-Demo-User: demo-user-1
```

```json
{
  "date": "2026-10-10",
  "seatNumber": 1
}
```

| フィールド | 型 | 必須 | 入力条件 |
| --- | --- | --- | --- |
| date | string（date） | 必須 | 実在する日付かつ日本時間で当日以降 |
| seatNumber | integer | 必須 | 座席マスタに存在する座席番号 |

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

| フィールド | 型 | 内容 |
| --- | --- | --- |
| id | string（UUID） | 作成した予約のID。キャンセルAPIの`{id}`に指定する |
| date | string（date） | 予約対象日 |
| seatNumber | integer | 予約した座席番号 |

### ステータスコード

| HTTPステータス | 条件 |
| --- | --- |
| 201 Created | 予約成功 |
| 400 Bad Request | 必須項目の未指定・null、JSONや型の不正、不正な日付・過去日、マスタに存在しない座席番号、または共通仕様の入力エラー |
| 409 Conflict | 同一日付・同一座席が予約済み |
| 500 Internal Server Error | システムエラー |

## 5. 予約キャンセル

### リクエスト

```http
DELETE /api/reservations/550e8400-e29b-41d4-a716-446655440000
X-Demo-User: demo-user-1
```

| パラメータ | 場所 | 型 | 必須 | 内容 |
| --- | --- | --- | --- | --- |
| id | パス | string（UUID） | 必須 | キャンセル対象の予約ID |

リクエスト本文は送信しない。

### 処理ルール

- 対象の予約が存在しない場合は`404 Not Found`を返す。
- 予約の`user_id`と操作ユーザーが一致しない場合は、削除せず`403 Forbidden`を返す。
- 操作ユーザー自身の予約のみ、トランザクション内でDELETEする。
- 予約履歴・キャンセル履歴は保持しない。
- 削除済みの予約IDに再度リクエストした場合は`404 Not Found`を返す。

### 成功レスポンス

コミット成功後に`204 No Content`を返す。レスポンス本文は返さない。

### ステータスコード

| HTTPステータス | 条件 |
| --- | --- |
| 204 No Content | キャンセル成功 |
| 400 Bad Request | 予約IDのUUID形式不正、または共通仕様の入力エラー |
| 403 Forbidden | 他ユーザーの予約 |
| 404 Not Found | 予約IDが存在しない |
| 500 Internal Server Error | システムエラー |

Phase 3で認可モデルを確定する際、存在を秘匿するために他ユーザーの予約を`404 Not Found`として扱う選択肢も比較する。

## 6. エラーレスポンス

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

| フィールド | 型 | 内容 |
| --- | --- | --- |
| type | string（URI参照） | 省略可能。省略時は`about:blank`として扱う。Phase 1で明示する場合も`about:blank`とする |
| title | string | HTTPステータスに対応する概要 |
| status | integer | 実際のHTTPステータスコードと一致させる |
| detail | string | エラー内容の説明。従来の`message`に代えて使用する |
| instance | string（URI参照） | エラーが発生したリクエストのパス |

本APIでは`title`、`status`、`detail`、`instance`を必ず返す。Phase 1では`type`の省略を許容し、上記のレスポンス例でも省略している。クライアントは`type`が省略されている場合、RFC 9457に従って`about:blank`として扱う。

| HTTPステータス | title | detailの例 |
| --- | --- | --- |
| 400 Bad Request | Bad Request | リクエストの内容が不正です。 |
| 403 Forbidden | Forbidden | 指定された予約はキャンセルできません。 |
| 404 Not Found | Not Found | 指定された予約が見つかりません。 |
| 409 Conflict | Conflict | 指定された日付・座席は予約済みです。 |
| 500 Internal Server Error | Internal Server Error | システムエラーが発生しました。 |

エラー本文に他ユーザーのユーザー情報や、リクエストで指定されたもの以外の予約IDを含めない。`instance`にはクエリ文字列を含めない。システムエラーの内部情報やSQL、制約名、スタックトレースはレスポンスに含めない。
