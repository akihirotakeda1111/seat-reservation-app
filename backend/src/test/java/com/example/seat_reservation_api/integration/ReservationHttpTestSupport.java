package com.example.seat_reservation_api.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.example.seat_reservation_api.support.TestJwtSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP関連の結合テスト共通関数 */
final class ReservationHttpTestSupport {
    private static final String RESERVATIONS = "/api/reservations";
    private final ObjectMapper mapper;
    private final ReservationIntegrationTestSupport support;
    private final HttpClient client;
    private final int port;

    ReservationHttpTestSupport(ObjectMapper mapper, ReservationIntegrationTestSupport support,
            HttpClient client, int port) {
        // 呼び出し元のクライアントと起動ポートを使用する。クライアントの終了は呼び出し元が行う。
        this.mapper = mapper;
        this.support = support;
        this.client = client;
        this.port = port;
    }

    String body(LocalDate date, int seat) {
        // 本文生成：指定日付・座席番号を予約登録APIのJSON形式にする。
        return "{\"date\":\"%s\",\"seatNumber\":%d}".formatted(date, seat);
    }

    HttpResponse<String> post(LocalDate date, int seat, String user) throws Exception {
        // 予約操作：指定ユーザーで予約本文を送信し、応答を呼び出し元へ返す。
        return request("POST", RESERVATIONS, user, body(date, seat));
    }

    HttpResponse<String> delete(String id, String user) throws Exception {
        // 取消操作：本文なしで送信する。IDは不正形式の検証もできるよう文字列で受け取る。
        return request("DELETE", RESERVATIONS + "/" + id, user, null);
    }

    HttpResponse<String> request(String method, String path, String user, String body)
            throws Exception {
        return requestWithToken(method, path,
                user == null ? null : TestJwtSupport.userToken(user), body);
    }

    HttpResponse<String> requestWithToken(String method, String path, String token, String body)
            throws Exception {
        return requestWithToken(method, path, token, body, Map.of());
    }

    HttpResponse<String> requestWithToken(String method, String path, String token, String body,
            Map<String, String> extraHeaders) throws Exception {
        // 接続準備：テストアプリのポートとパスを使い、リクエストの期限を30秒に設定する。
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(30));

        // ヘッダー設定：nullは認証ヘッダーなし、空文字はBearerトークンなしの要求にする。
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        extraHeaders.forEach(builder::header);

        // 送信・受信：HTTPメソッドと本文を指定して同期送信し、応答本文を文字列で受け取る。
        return client.send(
                builder.method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> response, int status, MediaType type) {
        // 応答確認：期待するステータスと互換性のあるContent-Typeを検証する。
        assertThat(response.statusCode()).as("HTTP response: %s", response.body())
                .isEqualTo(status);
        assertThat(MediaType
                .parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
                .isCompatibleWith(type)).isTrue();

        // 本文解析：後続の項目検証で利用するJSONツリーを返す。
        return mapper.readTree(response.body());
    }

    void assertProblem(HttpResponse<String> response, int status, String path, String detail) {
        if (status == 401) {
            assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow())
                    .startsWith("Bearer");
        }
        // 共通契約：指定ステータスとProblemDetailのメディアタイプを確認する。
        JsonNode root = json(response, status, MediaType.APPLICATION_PROBLEM_JSON);

        // エラー内容：ステータス・標準タイトル・対象パス・詳細メッセージを確認する。
        assertThat(root.path("status").asInt()).isEqualTo(status);
        assertThat(root.path("title").asString())
                .isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(root.path("instance").asString()).isEqualTo(path);
        assertThat(root.path("detail").asString()).isEqualTo(detail);
    }

    UUID assertCreated(HttpResponse<String> response, LocalDate date, int seat, String user) {
        // 応答確認：201とJSON形式、予約ID・日付・座席・Locationを検証する。
        JsonNode root = json(response, 201, MediaType.APPLICATION_JSON);
        UUID id = UUID.fromString(root.path("id").asString());
        assertThat(root.path("date").asString()).isEqualTo(date.toString());
        assertThat(root.path("seatNumber").asInt()).isEqualTo(seat);
        assertThat(response.headers().firstValue("Location")).contains(RESERVATIONS + "/" + id);

        // DB確認：応答IDの予約内容と所有者を確認し、後続操作で使えるIDを返す。
        support.assertStoredReservation(id, date, seat, user);
        return id;
    }

    void assertDeleted(HttpResponse<String> response) {
        // 応答確認：204・本文なしを検証する。削除後のDB状態は各ケースで別途確認する。
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }
}
