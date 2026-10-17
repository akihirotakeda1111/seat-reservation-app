package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.support.JwtAuthenticatedTest;
import com.example.seat_reservation_api.support.TestJwtSupport;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理者API・JWTを実HTTPとPostgreSQLで検証 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
@Import(ReservationIntegrationTestConfig.class)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class AdminReservationApiIntegrationTest extends JwtAuthenticatedTest {
    private static final String A = "user-a";
    private static final String B = "user-b";
    private static final String M = "admin-user";
    private static final String ADMIN = "/api/admin/reservations";
    private static final String GENERAL = "/api/reservations";
    private static final LocalDate D = LocalDate.now(ReservationIntegrationTestConfig.FIXED_CLOCK);

    @LocalServerPort
    int port;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    private HttpClient client;
    private ReservationIntegrationTestSupport db;
    private ReservationHttpTestSupport http;

    @BeforeEach
    void setUp() {
        // 準備：予約データを初期化し、座席マスタが揃っていることを確認する。
        db = new ReservationIntegrationTestSupport(reservations, jdbc);
        reservations.deleteAllInBatch();
        db.assertSeatMaster();

        // 接続準備：起動したアプリへ送信するHTTPクライアントと共通ヘルパーを用意する。
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        http = new ReservationHttpTestSupport(mapper, db, client, port);
    }

    @AfterEach
    void closeClient() {
        // 後処理：各ケースで使用したHTTPクライアントを閉じる。
        if (client != null) {
            client.close();
        }
    }

    @ParameterizedTest(name = "基準日から{0}日の一覧")
    @ValueSource(ints = {0, -1})
    @DisplayName("ADMIN-GET-01: 指定日の全所有者の予約を取得し別日を除外")
    void getsAllReservationsForDate(int days) throws Exception {
        // 準備：当日または過去日のA/Bの予約と、一覧から除外すべき別日の予約を用意する。
        LocalDate date = D.plusDays(days);
        UUID a = db.save(date, 1, A);
        UUID b = db.save(date, 2, B);
        db.save(date.plusDays(1), 1, A);
        var before = db.snapshot();

        // 操作・応答確認：管理者が一覧を取得し、指定日の予約情報だけが返ることを順序に依存せず確認する。
        JsonNode root = http.json(admin("GET", ADMIN + "?date=" + date, null),
                200, MediaType.APPLICATION_JSON);
        assertThat(root.path("date").asString()).isEqualTo(date.toString());
        assertThat(root.path("reservations").isArray()).isTrue();
        List<JsonNode> actual = new ArrayList<>();
        root.path("reservations").forEach(actual::add);
        assertThat(actual).containsExactlyInAnyOrder(
                mapper.readTree("""
                        {"id":"%s","seatNumber":1,"userId":"user-a"}
                        """.formatted(a)),
                mapper.readTree("""
                        {"id":"%s","seatNumber":2,"userId":"user-b"}
                        """.formatted(b)));

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("ADMIN-GET-02: 予約なしの日は空配列")
    void getsEmptyArray() throws Exception {
        // 準備：照会対象日は予約なしとし、保持すべき翌日の予約を用意する。
        db.save(D.plusDays(1), 1, A);
        var before = db.snapshot();

        // 操作・応答確認：管理者の照会が200となり、対象日と空配列が返ることを確認する。
        JsonNode root = http.json(admin("GET", ADMIN + "?date=" + D, null),
                200, MediaType.APPLICATION_JSON);
        assertThat(root.path("date").asString()).isEqualTo(D.toString());
        assertThat(root.path("reservations").isArray()).isTrue();
        assertThat(root.path("reservations").size()).isZero();

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid", "2026-02-30"})
    @DisplayName("ADMIN-GET-03: 日付入力不正は400")
    void invalidDate(String date) throws Exception {
        // 準備：不正な照会によって変更されないことを確認するため、予約を用意する。
        db.save(D, 1, A);

        // 操作・検証：有効な管理者JWTで不正な日付を送り、400とDB変更なしを確認する。
        assertRejected("GET", ADMIN + (date == null ? "" : "?date=" + date),
                TestJwtSupport.adminToken(M), 400, "リクエストの内容が不正です。");
    }

    @Test
    @DisplayName("ADMIN-GET-04: 一般ユーザーの一覧取得は403")
    void userCannotList() throws Exception {
        // 準備：一般ユーザーAの予約を用意する。
        db.save(D, 1, A);

        // 操作・検証：Aの管理者一覧取得を403で拒否し、予約を保持することを確認する。
        assertRejected("GET", ADMIN + "?date=" + D, TestJwtSupport.userToken(A),
                403, "操作権限がありません。");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "invalid-token")
    @DisplayName("ADMIN-GET-05: 未認証・不正トークンの一覧取得は401")
    void unauthenticatedList(String token) throws Exception {
        // 準備：認証失敗後も保持される予約を用意する。
        db.save(D, 1, A);

        // 操作・検証：日付は正常にし、JWTの欠落・不正による401とDB変更なしを確認する。
        assertRejected("GET", ADMIN + "?date=" + D, token, 401, "認証が必要です。");
    }

    @ParameterizedTest(name = "所有者={0}、基準日から{1}日")
    @MethodSource("cancellableReservations")
    @DisplayName("ADMIN-DEL-01: 自身・他人の当日・翌日の指定予約だけ削除")
    void deletesOnlyTargetReservation(String owner, int days) throws Exception {
        // 準備：所有者・日付の条件に応じた取消対象と、保持すべき別予約2件を用意する。
        UUID target = db.save(D.plusDays(days), 1, owner);
        db.save(D, 2, B);
        db.save(D.plusDays(1), 3, A);
        var remaining = db.snapshot().stream().filter(row -> !target.equals(row.get("id"))).toList();

        // 操作・応答確認：管理者専用APIで取り消し、204・本文なしを確認する。
        http.assertDeleted(admin("DELETE", ADMIN + "/" + target, null));

        // DB確認：指定した予約だけが消え、残る2件の全項目が維持される。
        assertThat(db.snapshot()).hasSize(2).isEqualTo(remaining);
    }

    static Stream<Arguments> cancellableReservations() {
        // 条件生成：他人・管理者自身の所有者2条件と、当日・翌日の2条件を組み合わせる。
        return Stream.of(A, M).flatMap(owner -> Stream.of(0, 1)
                .map(days -> Arguments.of(owner, days)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forbiddenDeletionCases")
    @DisplayName("ADMIN-DEL-02: 一般ユーザーは所有者・予約の有無によらず管理者DELETEを使用不可")
    void userCannotDelete(String description, boolean targetExists, String owner) throws Exception {
        // 準備：対象の所有者・存在有無を設定し、対象なしの場合も監視用の別予約を用意する。
        UUID target = targetExists ? db.save(D, 1, owner) : UUID.randomUUID();
        db.save(D, 2, B);

        // 操作・検証：一般ユーザーは対象の所有者・存在有無によらず403となり、DBが維持される。
        assertRejected("DELETE", ADMIN + "/" + target, TestJwtSupport.userToken(A),
                403, "操作権限がありません。");
    }

    static Stream<Arguments> forbiddenDeletionCases() {
        // 条件生成：自身の予約・他人の予約・対象なしを独立した拒否条件として渡す。
        return Stream.of(
                Arguments.of("自身の予約", true, A),
                Arguments.of("他人の予約", true, B),
                Arguments.of("対象なし", false, A));
    }

    @ParameterizedTest(name = "所有者={0}、{1}日前")
    @MethodSource("pastReservations")
    @DisplayName("ADMIN-DEL-03: 管理者も自身・他人の過去日は削除不可")
    void pastReservation(String owner, int daysAgo) throws Exception {
        // 準備：通常の予約APIでは登録できない過去日の予約をRepositoryで用意する。
        UUID target = db.save(D.minusDays(daysAgo), 1, owner);

        // 操作・検証：所有者が誰でも管理者の取消は400となり、元予約の全項目が維持される。
        assertRejected("DELETE", ADMIN + "/" + target, TestJwtSupport.adminToken(M),
                400, "過去の予約はキャンセルできません。");
    }

    static Stream<Arguments> pastReservations() {
        // 条件生成：他人・管理者自身の予約それぞれについて、1日前と30日前を検証する。
        return Stream.of(A, M).flatMap(owner -> Stream.of(1, 30)
                .map(daysAgo -> Arguments.of(owner, daysAgo)));
    }

    @Test
    @DisplayName("ADMIN-DEL-04: 存在しないUUIDは404")
    void missingReservation() throws Exception {
        // 準備：存在しないIDへの操作で変更されない、別の予約を用意する。
        db.save(D, 1, A);

        // 操作・検証：未登録UUIDを指定し、404と既存予約の保持を確認する。
        assertRejected("DELETE", ADMIN + "/" + UUID.randomUUID(), TestJwtSupport.adminToken(M),
                404, "指定された予約が見つかりません。");
    }

    @Test
    @DisplayName("ADMIN-DEL-04: UUID形式不正は400")
    void malformedId() throws Exception {
        // 準備：形式不正のIDで削除されないことを確認するため、予約を用意する。
        db.save(D, 1, A);

        // 操作・検証：有効な管理者JWTと不正IDを送り、400とDB変更なしを確認する。
        assertRejected("DELETE", ADMIN + "/invalid", TestJwtSupport.adminToken(M),
                400, "リクエストの内容が不正です。");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "invalid-token")
    @DisplayName("ADMIN-DEL-05: 未認証・不正トークンの削除は401")
    void unauthenticatedDelete(String token) throws Exception {
        // 準備：認証がなければ削除できない、実在する予約を用意する。
        UUID target = db.save(D, 1, A);

        // 操作・検証：正常な予約IDで認証失敗を再現し、401と対象予約の保持を確認する。
        assertRejected("DELETE", ADMIN + "/" + target, token, 401, "認証が必要です。");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("ADMIN-OWN-01: 管理者も一般DELETEでは他人の予約を削除不可")
    void generalDeleteChecksOwner(int days) throws Exception {
        // 準備：一般ユーザーAが所有する当日または前日の予約を用意する。
        UUID target = db.save(D.plusDays(days), 1, A);

        // 操作・検証：管理者でも一般APIでは所有者判定を受け、過去日判定より先に403となる。
        assertRejected("DELETE", GENERAL + "/" + target, TestJwtSupport.adminToken(M),
                403, "指定された予約はキャンセルできません。");
    }

    @Test
    @DisplayName("ADMIN-OWN-02: 管理者が一般APIで照会・予約・自身の取消")
    void adminUsesGeneralApis() throws Exception {
        // 準備：一連の操作を通して保持すべき、他ユーザーの予約を用意する。
        db.save(D, 2, A);
        var before = db.snapshot();

        // 照会・確認：管理者も一般の座席一覧を取得でき、照会ではDBが変わらないことを確認する。
        JsonNode seats = http.json(admin("GET", "/api/seats?date=" + D, null),
                200, MediaType.APPLICATION_JSON);
        assertThat(seats.path("date").asString()).isEqualTo(D.toString());
        assertThat(seats.path("seats").size()).isEqualTo(30);
        assertThat(db.snapshot()).isEqualTo(before);

        // 登録・確認：管理者本人の所有で予約が追加され、他ユーザーの予約が維持される。
        UUID target = http.assertCreated(admin("POST", GENERAL, http.body(D, 1)), D, 1, M);
        assertThat(db.snapshot()).hasSize(2).containsAll(before);

        // 取消・確認：一般APIで自身の予約を削除すると204となり、DBが操作前の状態に戻る。
        http.assertDeleted(admin("DELETE", GENERAL + "/" + target, null));
        assertThat(db.snapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    @DisplayName("AUTH-01/02: 不正な署名・ClaimのJWTは401")
    void invalidJwt(String description, String token) throws Exception {
        // 準備：JWTが有効なら取り消せる、所有者Aの予約を用意する。
        UUID target = db.save(D, 1, A);

        // 操作・検証：署名またはClaimが不正なJWTを送り、401と予約の全項目保持を確認する。
        assertRejected("DELETE", GENERAL + "/" + target, token, 401, "認証が必要です。");
    }

    static Stream<Arguments> invalidTokens() {
        // 署名不正の準備：正常なJWTのヘッダー・本文を保ち、署名の1ビットだけを変更する。
        String valid = TestJwtSupport.userToken(A);
        String[] parts = valid.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        String invalidSignature = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        // 条件生成：署名不正と各Claimの不正を独立させる。時刻条件は許容差を十分に超える値とする。
        return Stream.of(
                Arguments.of("署名不正", invalidSignature),
                Arguments.of("期限切れ", TestJwtSupport.token(A, c -> c
                        .issuedAt(Instant.now().minusSeconds(1200))
                        .expiresAt(Instant.now().minusSeconds(600)))),
                Arguments.of("issuer不一致", TestJwtSupport.token(A, c -> c.issuer("https://other.example.test"))),
                Arguments.of("ID Token", TestJwtSupport.token(A, c -> c.claim("token_use", "id"))),
                Arguments.of("client_id不一致", TestJwtSupport.token(A, c -> c.claim("client_id", "other-client"))),
                Arguments.of("sub欠落", TestJwtSupport.token(A, c -> c.claims(m -> m.remove("sub")))),
                Arguments.of("sub空", TestJwtSupport.token("", c -> {})),
                Arguments.of("sub空白のみ", TestJwtSupport.token(" \t\n", c -> {})),
                Arguments.of("sub129文字", TestJwtSupport.userToken("a".repeat(129))),
                Arguments.of("未来のnbf", TestJwtSupport.token(A,
                        c -> c.notBefore(Instant.now().plusSeconds(300)))),
                Arguments.of("exp欠落", TestJwtSupport.token(A, c -> c.claims(m -> m.remove("exp")))));
    }

    @Test
    @DisplayName("AUTH-02: subが上限128文字なら認証して予約できる")
    void acceptsSubjectWith128Characters() throws Exception {
        // 準備：上限長のsubと、登録によって変更されない別ユーザーの予約を用意する。
        String subject = "a".repeat(128);
        db.save(D, 2, B);
        var before = db.snapshot();

        // 操作・応答確認：128文字のsubで認証・予約が成功し、DBにも同じ所有者が保存される。
        http.assertCreated(http.requestWithToken("POST", GENERAL,
                TestJwtSupport.userToken(subject), http.body(D, 1)), D, 1, subject);

        // DB確認：新しい予約が1件だけ追加され、既存予約の全項目が維持される。
        assertThat(db.snapshot()).hasSize(2).containsAll(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonAdminTokens")
    @DisplayName("AUTH-03: グループなし・不正形式では管理者権限を与えない")
    void nonAdminGroups(String description, String token) throws Exception {
        // 準備：照会前の予約を用意し、DB全項目を保存する。
        db.save(D, 1, A);
        var before = db.snapshot();

        // 認証確認：Group条件にかかわらずJWT自体は有効で、一般APIへアクセスできる。
        http.json(http.requestWithToken("GET", "/api/seats?date=" + D, token, null),
                200, MediaType.APPLICATION_JSON);

        // 認可確認：同じJWTでも管理者APIでは403となり、管理者権限が付与されない。
        assertRejected("GET", ADMIN + "?date=" + D, token, 403, "操作権限がありません。");

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }

    static Stream<Arguments> nonAdminTokens() {
        // 条件生成：Group欠落・別Group・形式不正だけを変え、その他の認証条件は有効に保つ。
        return Stream.of(
                Arguments.of("グループなし", TestJwtSupport.userToken(A)),
                Arguments.of("別グループ", TestJwtSupport.token(A, c -> c.claim("cognito:groups", List.of("USERS")))),
                Arguments.of("文字列単体", TestJwtSupport.token(A, c -> c.claim("cognito:groups", "ADMINS"))),
                Arguments.of("型が混在", TestJwtSupport.token(A, c -> c.claim("cognito:groups", List.of("ADMINS", 1)))));
    }

    @Test
    @DisplayName("AUTH-04: X-Demo-Userだけでは認証不可")
    void demoHeaderCannotAuthenticate() throws Exception {
        // 準備：未認証の登録要求で変更されない既存予約を用意する。
        db.save(D, 2, B);
        var before = db.snapshot();

        // 操作・応答確認：JWTなしで旧ヘッダーだけを送り、認証を迂回できず401になることを確認する。
        http.assertProblem(http.requestWithToken("POST", GENERAL, null, http.body(D, 1),
                Map.of("X-Demo-User", A)), 401, GENERAL, "認証が必要です。");

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AUTH-05: X-Demo-UserよりJWTの所有者を使用")
    void jwtDeterminesOwner() throws Exception {
        // 準備：所有者偽装の指定先となるBの既存予約と、DB状態を用意する。
        db.save(D, 2, B);
        var before = db.snapshot();

        // 操作・応答確認：AのJWTにBの旧ヘッダーを併記しても、登録所有者はAになる。
        http.assertCreated(http.requestWithToken("POST", GENERAL, TestJwtSupport.userToken(A),
                http.body(D, 1), Map.of("X-Demo-User", B)), D, 1, A);

        // DB確認：新しい予約が1件だけ追加され、既存予約の全項目が維持される。
        assertThat(db.snapshot()).hasSize(2).containsAll(before);
    }

    private HttpResponse<String> admin(String method, String path, String body) throws Exception {
        // 共通送信：管理者Groupを持つ署名済みJWTを付与し、指定メソッド・パスへ送信する。
        return http.requestWithToken(method, path, TestJwtSupport.adminToken(M), body);
    }

    private void assertRejected(String method, String path, String token, int status, String detail)
            throws Exception {
        // 準備：拒否される操作の直前にDB全項目を保存する。
        var before = db.snapshot();

        // 操作・応答確認：期待するステータスとProblemDetailを照合する。instanceからはクエリを除く。
        http.assertProblem(http.requestWithToken(method, path, token, null), status,
                path.split("\\?", 2)[0], detail);

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }
}
