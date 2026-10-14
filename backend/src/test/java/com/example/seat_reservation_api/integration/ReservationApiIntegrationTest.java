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
    class GetSeatsTests {
        @Test
        @DisplayName("GET-01: 全席空席")
        void allSeatsAvailable() throws Exception {
            List<Map<String, Object>> before = support.snapshot();
            assertAvailable(seats(getSeats(D.toString(), A)), 0);
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-02: 自分・他人の予約と空席が混在")
        void mixedSeatStates() throws Exception {
            UUID mine = support.save(D, 1, A);
            support.save(D, 2, B);
            List<Map<String, Object>> before = support.snapshot();
            JsonNode seats = seats(getSeats(D.toString(), A));
            assertThat(seats.get(0).path("status").asString()).isEqualTo("MINE");
            assertThat(seats.get(0).path("reservationId").asString()).isEqualTo(mine.toString());
            assertThat(seats.get(1).path("status").asString()).isEqualTo("OCCUPIED");
            assertThat(seats.get(1).has("reservationId")).isFalse();
            assertAvailable(seats, 2);
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-03: 別日の予約を除外")
        void excludesOtherDate() throws Exception {
            support.save(D.plusDays(1), 1, A);
            List<Map<String, Object>> before = support.snapshot();
            assertAvailable(seats(getSeats(D.toString(), A)), 0);
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-04: リクエストパラメータ不正の代表値")
        void invalidGetDate() throws Exception {
            List<Map<String, Object>> before = support.snapshot();
            httpSupport.assertProblem(getSeats("invalid", A), 400, "/api/seats",
                    "リクエストの内容が不正です。");
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("GET-05: ユーザーヘッダー不正")
        void invalidGetHeader(String user) throws Exception {
            List<Map<String, Object>> before = support.snapshot();
            httpSupport.assertProblem(getSeats(D.toString(), user), 400, "/api/seats",
                    "リクエストの内容が不正です。");
            assertThat(support.snapshot()).isEqualTo(before);
        }
    }

    @Nested
    class CreateReservationTests {
        @Test
        @DisplayName("POST-01: 当日予約")
        void reserveToday() throws Exception {
            httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);
            assertThat(support.snapshot()).hasSize(1);
        }

        @Test
        @DisplayName("POST-02: 当日予約後、同じ座席で翌日予約")
        void reserveSameSeatNextDay() throws Exception {
            UUID today = httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);
            List<Map<String, Object>> before = support.snapshot();
            UUID tomorrow = httpSupport.assertCreated(httpSupport.post(D.plusDays(1), 1, A), D.plusDays(1), 1, A);
            assertThat(tomorrow).isNotEqualTo(today);
            assertThat(support.snapshot()).hasSize(2).containsAll(before);
        }

        @Test
        @DisplayName("POST-03: 同じユーザーが同じ日付・座席を2回予約")
        void duplicateSameUser() throws Exception {
            assertDuplicate(A);
        }

        @Test
        @DisplayName("POST-04: 別ユーザーが同じ日付・座席を予約")
        void duplicateOtherUser() throws Exception {
            assertDuplicate(B);
        }

        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("POST-05: 同じ日付・座席へ同時POST")
        void concurrentReservations() throws Exception {
            CyclicBarrier start = new CyclicBarrier(2);
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<HttpResponse<String>> a = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return httpSupport.post(D, 1, A);
                });
                Future<HttpResponse<String>> b = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return httpSupport.post(D, 1, B);
                });
                HttpResponse<String> responseA = a.get(45, TimeUnit.SECONDS);
                HttpResponse<String> responseB = b.get(45, TimeUnit.SECONDS);
                assertThat(List.of(responseA.statusCode(), responseB.statusCode()))
                        .containsExactlyInAnyOrder(201, 409);
                boolean aWon = responseA.statusCode() == 201;
                httpSupport.assertCreated(aWon ? responseA : responseB, D, 1, aWon ? A : B);
                httpSupport.assertProblem(aWon ? responseB : responseA, 409, RESERVATIONS,
                        "指定された日付・座席は予約済みです。");
                assertThat(support.snapshot()).hasSize(1);
            }
        }

        @Test
        @DisplayName("POST-06: 前日予約")
        void pastDate() throws Exception {
            assertRejectedPost(httpSupport.body(D.minusDays(1), 1), A);
        }

        @Test
        @DisplayName("POST-07: マスタにない座席を予約")
        void unknownSeat() throws Exception {
            assertRejectedPost(httpSupport.body(D, 31), A);
        }

        @Test
        @DisplayName("POST-08: リクエストパラメータ不正の代表値")
        void missingSeatNumber() throws Exception {
            assertRejectedPost("{\"date\":\"" + D + "\"}", A);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("POST-09: ユーザーヘッダー不正")
        void invalidPostHeader(String user) throws Exception {
            assertRejectedPost(httpSupport.body(D, 1), user);
        }

        @Test
        @DisplayName("POST-10: リクエストボディ不正")
        void malformedJson() throws Exception {
            assertRejectedPost("{\"date\":\"" + D + "\",\"seatNumber\":1", A);
        }
    }

    @Nested
    class DeleteReservationTests {
        @Test
        @DisplayName("DEL-01: 自分の予約を1件だけ削除し、同日・別日の予約を保持")
        void deletesOnlyTarget() throws Exception {
            UUID target = support.save(D, 1, A);
            support.save(D, 2, A);
            support.save(D.plusDays(1), 1, A);
            List<Map<String, Object>> remaining =
                    support.snapshot().stream().filter(row -> !target.equals(row.get("id"))).toList();
            httpSupport.assertDeleted(httpSupport.delete(target.toString(), A));
            assertThat(support.snapshot()).hasSize(2).isEqualTo(remaining);
        }

        @Test
        @DisplayName("DEL-02: 他ユーザーの予約を削除")
        void cannotDeleteOthersReservation() throws Exception {
            UUID target = support.save(D, 1, B);
            assertRejectedDelete(target.toString(), A, 403,
                    "指定された予約はキャンセルできません。");
        }

        @Test
        @DisplayName("DEL-03: 存在しない予約を削除")
        void unknownReservation() throws Exception {
            assertRejectedDelete(UUID.randomUUID().toString(), A, 404,
                    "指定された予約が見つかりません。");
        }

        @Test
        @DisplayName("DEL-04: 存在する予約を2回削除")
        void deleteTwice() throws Exception {
            String target = support.save(D, 1, A).toString();
            httpSupport.assertDeleted(httpSupport.delete(target, A));
            assertThat(support.snapshot()).isEmpty();
            assertRejectedDelete(target, A, 404,
                    "指定された予約が見つかりません。");
        }

        @Test
        @DisplayName("DEL-05: リクエストパラメータ不正の代表値")
        void invalidReservationId() throws Exception {
            support.save(D, 1, A);
            assertRejectedDelete("invalid", A, 400,
                    "リクエストの内容が不正です。");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("DEL-06: ユーザーヘッダー不正")
        void invalidDeleteHeader(String user) throws Exception {
            UUID target = support.save(D, 1, A);
            assertRejectedDelete(target.toString(), user, 400,
                    "リクエストの内容が不正です。");
        }

        @Test
        @DisplayName("DEL-07: 過去日の予約はキャンセルできない")
        void cannotDeletePastReservation() throws Exception {
            UUID target = support.save(D.minusDays(1), 1, A);
            List<Map<String, Object>> before = support.snapshot();

            HttpResponse<String> response = httpSupport.delete(target.toString(), A);

            httpSupport.assertProblem(response, 400, RESERVATIONS + "/" + target,
                    "過去の予約はキャンセルできません。");
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("DEL-08: 他人の過去日の予約には400ではなく403を返す")
        void cannotDeleteOthersPastReservation() throws Exception {
            UUID target = support.save(D.minusDays(1), 1, B);

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
        httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);
        List<Map<String, Object>> before = support.snapshot();
        assertThat(before).hasSize(1);
        httpSupport.assertProblem(httpSupport.post(D, 1, secondUser), 409, RESERVATIONS,
                "指定された日付・座席は予約済みです。");
        assertThat(support.snapshot()).isEqualTo(before);
    }

    private void assertRejectedPost(String body, String user) throws Exception {
        List<Map<String, Object>> before = support.snapshot();
        httpSupport.assertProblem(httpSupport.request("POST", RESERVATIONS, user, body), 400, RESERVATIONS,
                "リクエストの内容が不正です。");
        assertThat(before).isEmpty();
        assertThat(support.snapshot()).isEqualTo(before);
    }

    private void assertRejectedDelete(String id, String user, int status, String detail) throws Exception {
        List<Map<String, Object>> before = support.snapshot();
        httpSupport.assertProblem(httpSupport.delete(id, user), status, RESERVATIONS + "/" + id, detail);
        assertThat(support.snapshot()).isEqualTo(before);
    }

}
