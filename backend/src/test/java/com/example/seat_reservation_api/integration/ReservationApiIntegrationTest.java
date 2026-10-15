package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.repository.ReservationRepository;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
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

/** doc/testing/api-integration-test-matrix.md の実HTTP・実DBによる結合テスト。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
@Import(ReservationIntegrationTestConfig.class)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class ReservationApiIntegrationTest {

    private static final String A = "demo-user-1";
    private static final String B = "demo-user-2";
    private static final String RESERVATIONS = "/api/reservations";
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
    private ReservationIntegrationTestSupport support;
    private ReservationHttpTestSupport httpSupport;

    @BeforeEach
    void setUp() {
        support = new ReservationIntegrationTestSupport(reservations, jdbc);
        reservations.deleteAllInBatch();
        support.assertSeatMaster();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        httpSupport = new ReservationHttpTestSupport(mapper, support, client, port);
    }

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Nested
    @DisplayName("座席一覧取得")
    class GetSeatsTests {
        @Test
        @DisplayName("GET-01: 全席空席")
        void allSeatsAvailable() throws Exception {
            // 準備：照会前の空のDB状態を保存する。
            List<Map<String, Object>> before = support.snapshot();

            // 操作・応答確認：当日の座席一覧を取得し、30席すべて空席であることを確認する。
            assertAvailable(seats(getSeats(D.toString(), A)), 0);

            // DB確認：照会によって予約が変更されないことを確認する。
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-02: 自分・他人の予約と空席が混在")
        void mixedSeatStates() throws Exception {
            // 準備：自分と他ユーザーの予約を1件ずつ用意し、DB状態を保存する。
            UUID mine = support.save(D, 1, A);
            support.save(D, 2, B);
            List<Map<String, Object>> before = support.snapshot();

            // 操作・応答確認：自席だけ予約IDが返り、他人の予約席と空席が区別されることを確認する。
            JsonNode seats = seats(getSeats(D.toString(), A));
            assertThat(seats.get(0).path("status").asString()).isEqualTo("MINE");
            assertThat(seats.get(0).path("reservationId").asString()).isEqualTo(mine.toString());
            assertThat(seats.get(1).path("status").asString()).isEqualTo("OCCUPIED");
            assertThat(seats.get(1).has("reservationId")).isFalse();
            assertAvailable(seats, 2);

            // DB確認：照会後も予約の全項目が維持される。
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-03: 別日の予約を除外")
        void excludesOtherDate() throws Exception {
            // 準備：翌日の予約を用意し、DB状態を保存する。
            support.save(D.plusDays(1), 1, A);
            List<Map<String, Object>> before = support.snapshot();

            // 操作・応答確認：当日の一覧には翌日の予約が含まれず、全席空席となる。
            assertAvailable(seats(getSeats(D.toString(), A)), 0);

            // DB確認：翌日の予約が変更されないことを確認する。
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-04: リクエストパラメータ不正の代表値")
        void invalidGetDate() throws Exception {
            // 準備：不正な照会の前にDB状態を保存する。
            List<Map<String, Object>> before = support.snapshot();

            // 操作・応答確認：日付形式が不正なGETに対し、400とProblemDetailを確認する。
            httpSupport.assertProblem(getSeats("invalid", A), 400, "/api/seats",
                    "リクエストの内容が不正です。");

            // DB確認：リクエスト拒否後も予約が変更されない。
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("GET-05: ユーザーヘッダー不正")
        void invalidGetHeader(String user) throws Exception {
            // 準備：ヘッダー不正の照会前にDB状態を保存する。
            List<Map<String, Object>> before = support.snapshot();

            // 操作・応答確認：ユーザーの未指定・空文字・未許可値が400となることを確認する。
            httpSupport.assertProblem(getSeats(D.toString(), user), 400, "/api/seats",
                    "リクエストの内容が不正です。");

            // DB確認：リクエスト拒否後も予約が変更されない。
            assertThat(support.snapshot()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("予約登録")
    class CreateReservationTests {
        @Test
        @DisplayName("POST-01: 当日予約")
        void reserveToday() throws Exception {
            // 操作・応答確認：当日の空席を予約し、201と応答IDに対応するDB内容を確認する。
            httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);

            // 最終確認：登録された予約が1件だけであることを確認する。
            assertThat(support.snapshot()).hasSize(1);
        }

        @Test
        @DisplayName("POST-02: 当日予約後、同じ座席で翌日予約")
        void reserveSameSeatNextDay() throws Exception {
            // 準備：当日の予約をHTTPで登録し、保持すべきDB状態を保存する。
            UUID today = httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);
            List<Map<String, Object>> before = support.snapshot();

            // 操作・応答確認：同じ座席を翌日にも予約し、201とDB内容を確認する。
            UUID tomorrow = httpSupport.assertCreated(httpSupport.post(D.plusDays(1), 1, A), D.plusDays(1), 1, A);

            // 最終確認：予約IDは別になり、当日の予約を保持したまま2件となる。
            assertThat(tomorrow).isNotEqualTo(today);
            assertThat(support.snapshot()).hasSize(2).containsAll(before);
        }

        @Test
        @DisplayName("POST-03: 同じユーザーが同じ日付・座席を2回予約")
        void duplicateSameUser() throws Exception {
            // 共通処理：Aの予約後にAが同じ日付・座席を再予約し、409と元予約の保持を確認する。
            assertDuplicate(A);
        }

        @Test
        @DisplayName("POST-04: 別ユーザーが同じ日付・座席を予約")
        void duplicateOtherUser() throws Exception {
            // 共通処理：Aの予約後にBが同じ日付・座席を予約し、409と元予約の保持を確認する。
            assertDuplicate(B);
        }

        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("POST-05: 同じ日付・座席へ同時POST")
        void concurrentReservations() throws Exception {
            // 準備：2件のPOSTの開始を同期するバリアと、並列実行用のWorkerを用意する。
            CyclicBarrier start = new CyclicBarrier(2);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                // 並列操作：A/Bがバリアで合流した後、同じ日付・座席へPOSTする。
                Future<HttpResponse<String>> a = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return httpSupport.post(D, 1, A);
                });
                Future<HttpResponse<String>> b = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return httpSupport.post(D, 1, B);
                });

                // 結果回収：両リクエストの応答を期限付きで待つ。
                HttpResponse<String> responseA = a.get(45, TimeUnit.SECONDS);
                HttpResponse<String> responseB = b.get(45, TimeUnit.SECONDS);

                // 応答確認：勝者を固定せず、201が1件・409が1件となり、各応答が契約に従うことを確認する。
                assertThat(List.of(responseA.statusCode(), responseB.statusCode()))
                        .containsExactlyInAnyOrder(201, 409);
                boolean aWon = responseA.statusCode() == 201;
                httpSupport.assertCreated(aWon ? responseA : responseB, D, 1, aWon ? A : B);
                httpSupport.assertProblem(aWon ? responseB : responseA, 409, RESERVATIONS,
                        "指定された日付・座席は予約済みです。");

                // DB確認：勝者の予約だけが1件残る。Workerはtry終了時に閉じる。
                assertThat(support.snapshot()).hasSize(1);
            }
        }

        @Test
        @DisplayName("POST-06: 前日予約")
        void pastDate() throws Exception {
            // 共通処理：前日の予約を送信し、400とDB変更なしを確認する。
            assertRejectedPost(httpSupport.body(D.minusDays(1), 1), A);
        }

        @Test
        @DisplayName("POST-07: マスタにない座席を予約")
        void unknownSeat() throws Exception {
            // 共通処理：マスタにない座席31を指定し、400とDB変更なしを確認する。
            assertRejectedPost(httpSupport.body(D, 31), A);
        }

        @Test
        @DisplayName("POST-08: リクエストパラメータ不正の代表値")
        void missingSeatNumber() throws Exception {
            // 共通処理：座席番号を省略した本文を送信し、400とDB変更なしを確認する。
            assertRejectedPost("{\"date\":\"" + D + "\"}", A);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("POST-09: ユーザーヘッダー不正")
        void invalidPostHeader(String user) throws Exception {
            // 共通処理：ユーザーの未指定・空文字・未許可値で予約し、400とDB変更なしを確認する。
            assertRejectedPost(httpSupport.body(D, 1), user);
        }

        @Test
        @DisplayName("POST-10: リクエストボディ不正")
        void malformedJson() throws Exception {
            // 共通処理：閉じ括弧がないJSONを送信し、400とDB変更なしを確認する。
            assertRejectedPost("{\"date\":\"" + D + "\",\"seatNumber\":1", A);
        }
    }

    @Nested
    @DisplayName("キャンセル")
    class DeleteReservationTests {
        @Test
        @DisplayName("DEL-01: 自分の予約を1件だけ削除し、同日・別日の予約を保持")
        void deletesOnlyTarget() throws Exception {
            // 準備：取消対象と、保持すべき同日別座席・翌日の予約を用意する。
            UUID target = support.save(D, 1, A);
            support.save(D, 2, A);
            support.save(D.plusDays(1), 1, A);
            List<Map<String, Object>> remaining =
                    support.snapshot().stream().filter(row -> !target.equals(row.get("id"))).toList();

            // 操作・応答確認：所有者が対象予約を取り消し、204・本文なしを確認する。
            httpSupport.assertDeleted(httpSupport.delete(target.toString(), A));

            // DB確認：対象だけが消え、残る2件の全項目が維持される。
            assertThat(support.snapshot()).hasSize(2).isEqualTo(remaining);
        }

        @Test
        @DisplayName("DEL-02: 他ユーザーの予約を削除")
        void cannotDeleteOthersReservation() throws Exception {
            // 準備：Bが所有する予約を用意する。
            UUID target = support.save(D, 1, B);

            // 操作・検証：Aによる取消が403となり、Bの予約が維持されることを確認する。
            assertRejectedDelete(target.toString(), A, 403,
                    "指定された予約はキャンセルできません。");
        }

        @Test
        @DisplayName("DEL-03: 存在しない予約を削除")
        void unknownReservation() throws Exception {
            // 共通処理：未登録IDを削除し、404とDB変更なしを確認する。
            assertRejectedDelete(UUID.randomUUID().toString(), A, 404,
                    "指定された予約が見つかりません。");
        }

        @Test
        @DisplayName("DEL-04: 存在する予約を2回削除")
        void deleteTwice() throws Exception {
            // 準備：Aが所有する予約を用意する。
            String target = support.save(D, 1, A).toString();

            // 初回操作・確認：取消が204となり、DBに予約が残らないことを確認する。
            httpSupport.assertDeleted(httpSupport.delete(target, A));
            assertThat(support.snapshot()).isEmpty();

            // 再操作・確認：同じIDの取消は404となり、削除済みの状態が維持される。
            assertRejectedDelete(target, A, 404,
                    "指定された予約が見つかりません。");
        }

        @Test
        @DisplayName("DEL-05: リクエストパラメータ不正の代表値")
        void invalidReservationId() throws Exception {
            // 準備：不正なIDで削除されないことを確認するため、予約を用意する。
            support.save(D, 1, A);

            // 操作・検証：UUID形式でないIDの取消が400となり、予約が維持される。
            assertRejectedDelete("invalid", A, 400,
                    "リクエストの内容が不正です。");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("DEL-06: ユーザーヘッダー不正")
        void invalidDeleteHeader(String user) throws Exception {
            // 準備：取消対象となるAの予約を用意する。
            UUID target = support.save(D, 1, A);

            // 操作・検証：ユーザーの未指定・空文字・未許可値が400となり、予約が維持される。
            assertRejectedDelete(target.toString(), user, 400,
                    "リクエストの内容が不正です。");
        }

        @Test
        @DisplayName("DEL-07: 過去日の予約はキャンセルできない")
        void cannotDeletePastReservation() throws Exception {
            // 準備：Aの過去日の予約と、変更前のDB全項目を保存する。
            UUID target = support.save(D.minusDays(1), 1, A);
            List<Map<String, Object>> before = support.snapshot();

            // 操作：所有者Aが過去日の予約を取り消す。
            HttpResponse<String> response = httpSupport.delete(target.toString(), A);

            // 結果確認：過去日による400とProblemDetailを確認し、元予約の全項目が維持される。
            httpSupport.assertProblem(response, 400, RESERVATIONS + "/" + target,
                    "過去の予約はキャンセルできません。");
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("DEL-08: 他人の過去日の予約には400ではなく403を返す")
        void cannotDeleteOthersPastReservation() throws Exception {
            // 準備：Bが所有する過去日の予約を用意する。
            UUID target = support.save(D.minusDays(1), 1, B);

            // 操作・検証：Aの取消は所有者確認で403となり、予約が維持されることを確認する。
            assertRejectedDelete(target.toString(), A, 403,
                    "指定された予約はキャンセルできません。");
        }
    }


    private HttpResponse<String> getSeats(String date, String user) throws Exception {
        HttpResponse<String> response = httpSupport.request("GET", "/api/seats?date=" + date, user, null);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return response;
    }

    private JsonNode seats(HttpResponse<String> response) {
        JsonNode root = httpSupport.json(response, 200, MediaType.APPLICATION_JSON);
        assertThat(root.path("date").asString()).isEqualTo(D.toString());
        assertThat(root.has("userId")).isFalse();
        JsonNode seats = root.path("seats");
        assertThat(seats.isArray()).isTrue();
        assertThat(seats.size()).isEqualTo(30);
        for (int i = 0; i < 30; i++) {
            assertThat(seats.get(i).path("seatNumber").asInt()).isEqualTo(i + 1);
            assertThat(seats.get(i).has("userId")).isFalse();
        }
        return seats;
    }

    private void assertAvailable(JsonNode seats, int from) {
        for (int i = from; i < seats.size(); i++) {
            assertThat(seats.get(i).path("status").asString()).isEqualTo("AVAILABLE");
            assertThat(seats.get(i).has("reservationId")).isFalse();
        }
    }

    private void assertDuplicate(String secondUser) throws Exception {
        // 準備：Aの予約を登録し、重複POST前のDB状態を保存する。
        httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);
        List<Map<String, Object>> before = support.snapshot();
        assertThat(before).hasSize(1);

        // 操作・応答確認：指定ユーザーの重複予約が409となることを確認する。
        httpSupport.assertProblem(httpSupport.post(D, 1, secondUser), 409, RESERVATIONS,
                "指定された日付・座席は予約済みです。");

        // DB確認：元予約の全項目を保持し、重複行が追加されない。
        assertThat(support.snapshot()).isEqualTo(before);
    }

    private void assertRejectedPost(String body, String user) throws Exception {
        // 準備：不正なPOST前のDB状態を保存する。
        List<Map<String, Object>> before = support.snapshot();

        // 操作・応答確認：指定本文・ユーザーでPOSTし、400とProblemDetailを確認する。
        httpSupport.assertProblem(httpSupport.request("POST", RESERVATIONS, user, body), 400, RESERVATIONS,
                "リクエストの内容が不正です。");

        // DB確認：事前状態が空であり、拒否後も予約が追加されない。
        assertThat(before).isEmpty();
        assertThat(support.snapshot()).isEqualTo(before);
    }

    private void assertRejectedDelete(String id, String user, int status, String detail) throws Exception {
        // 準備：削除を拒否する前のDB全項目を保存する。
        List<Map<String, Object>> before = support.snapshot();

        // 操作・応答確認：指定ID・ユーザーでDELETEし、期待するエラー応答を確認する。
        httpSupport.assertProblem(httpSupport.delete(id, user), status, RESERVATIONS + "/" + id, detail);

        // DB確認：拒否後もすべての予約が変更されない。
        assertThat(support.snapshot()).isEqualTo(before);
    }

}
