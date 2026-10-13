package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.repository.ReservationRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** doc/testing/api-integration-test-matrix.md の実HTTP・実DBによる結合テスト。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"local", "test"})
@Import(ReservationApiIntegrationTest.IntegrationConfig.class)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class ReservationApiIntegrationTest {

    private static final String A = "demo-user-1";
    private static final String B = "demo-user-2";
    private static final String RESERVATIONS = "/api/reservations";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-10T15:30:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final LocalDate D = LocalDate.now(FIXED_CLOCK);

    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }

        @Bean
        @Primary
        Clock integrationClock() {
            return FIXED_CLOCK;
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper mapper;

    private HttpClient client;

    @BeforeEach
    void setUp() {
        reservations.deleteAllInBatch();
        assertThat(jdbc.queryForList("select seat_number from seats order by seat_number",
                Integer.class)).containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 30).boxed().toList());
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
            List<Map<String, Object>> before = snapshot();
            assertAvailable(seats(getSeats(D.toString(), A)), 0);
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-02: 自分・他人の予約と空席が混在")
        void mixedSeatStates() throws Exception {
            UUID mine = save(D, 1, A);
            save(D, 2, B);
            List<Map<String, Object>> before = snapshot();
            JsonNode seats = seats(getSeats(D.toString(), A));
            assertThat(seats.get(0).path("status").asString()).isEqualTo("MINE");
            assertThat(seats.get(0).path("reservationId").asString()).isEqualTo(mine.toString());
            assertThat(seats.get(1).path("status").asString()).isEqualTo("OCCUPIED");
            assertThat(seats.get(1).has("reservationId")).isFalse();
            assertAvailable(seats, 2);
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-03: 別日の予約を除外")
        void excludesOtherDate() throws Exception {
            save(D.plusDays(1), 1, A);
            List<Map<String, Object>> before = snapshot();
            assertAvailable(seats(getSeats(D.toString(), A)), 0);
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("GET-04: リクエストパラメータ不正の代表値")
        void invalidGetDate() throws Exception {
            List<Map<String, Object>> before = snapshot();
            assertProblem(getSeats("invalid", A), 400, "/api/seats");
            assertThat(snapshot()).isEqualTo(before);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("GET-05: ユーザーヘッダー不正")
        void invalidGetHeader(String user) throws Exception {
            List<Map<String, Object>> before = snapshot();
            assertProblem(getSeats(D.toString(), user), 400, "/api/seats");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    @Nested
    class CreateReservationTests {
        @Test
        @DisplayName("POST-01: 当日予約")
        void reserveToday() throws Exception {
            assertCreated(post(D, 1, A), D, 1, A);
            assertThat(snapshot()).hasSize(1);
        }

        @Test
        @DisplayName("POST-02: 当日予約後、同じ座席で翌日予約")
        void reserveSameSeatNextDay() throws Exception {
            UUID today = assertCreated(post(D, 1, A), D, 1, A);
            List<Map<String, Object>> before = snapshot();
            UUID tomorrow = assertCreated(post(D.plusDays(1), 1, A), D.plusDays(1), 1, A);
            assertThat(tomorrow).isNotEqualTo(today);
            assertThat(snapshot()).hasSize(2).containsAll(before);
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
                    return post(D, 1, A);
                });
                Future<HttpResponse<String>> b = executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return post(D, 1, B);
                });
                HttpResponse<String> responseA = a.get(45, TimeUnit.SECONDS);
                HttpResponse<String> responseB = b.get(45, TimeUnit.SECONDS);
                assertThat(List.of(responseA.statusCode(), responseB.statusCode()))
                        .containsExactlyInAnyOrder(201, 409);
                boolean aWon = responseA.statusCode() == 201;
                assertCreated(aWon ? responseA : responseB, D, 1, aWon ? A : B);
                assertProblem(aWon ? responseB : responseA, 409, RESERVATIONS);
                assertThat(snapshot()).hasSize(1);
            }
        }

        @Test
        @DisplayName("POST-06: 前日予約")
        void pastDate() throws Exception {
            assertRejectedPost(body(D.minusDays(1), 1), A);
        }

        @Test
        @DisplayName("POST-07: マスタにない座席を予約")
        void unknownSeat() throws Exception {
            assertRejectedPost(body(D, 31), A);
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
            assertRejectedPost(body(D, 1), user);
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
            UUID target = save(D, 1, A);
            save(D, 2, A);
            save(D.plusDays(1), 1, A);
            List<Map<String, Object>> remaining =
                    snapshot().stream().filter(row -> !target.equals(row.get("id"))).toList();
            assertDeleted(delete(target.toString(), A));
            assertThat(snapshot()).hasSize(2).isEqualTo(remaining);
        }

        @Test
        @DisplayName("DEL-02: 他ユーザーの予約を削除")
        void cannotDeleteOthersReservation() throws Exception {
            UUID target = save(D, 1, B);
            assertRejectedDelete(target.toString(), A, 403);
        }

        @Test
        @DisplayName("DEL-03: 存在しない予約を削除")
        void unknownReservation() throws Exception {
            assertRejectedDelete(UUID.randomUUID().toString(), A, 404);
        }

        @Test
        @DisplayName("DEL-04: 存在する予約を2回削除")
        void deleteTwice() throws Exception {
            String target = save(D, 1, A).toString();
            assertDeleted(delete(target, A));
            assertThat(snapshot()).isEmpty();
            assertRejectedDelete(target, A, 404);
        }

        @Test
        @DisplayName("DEL-05: リクエストパラメータ不正の代表値")
        void invalidReservationId() throws Exception {
            save(D, 1, A);
            assertRejectedDelete("invalid", A, 400);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-user")
        @DisplayName("DEL-06: ユーザーヘッダー不正")
        void invalidDeleteHeader(String user) throws Exception {
            UUID target = save(D, 1, A);
            assertRejectedDelete(target.toString(), user, 400);
        }

        @Test
        @DisplayName("DEL-07: 過去日の予約はキャンセルできない")
        void cannotDeletePastReservation() throws Exception {
            UUID target = save(D.minusDays(1), 1, A);
            List<Map<String, Object>> before = snapshot();

            HttpResponse<String> response = delete(target.toString(), A);

            JsonNode root = json(response, 400, MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(root.path("status").asInt()).isEqualTo(400);
            assertThat(root.path("title").asString()).isEqualTo("Bad Request");
            assertThat(root.path("detail").asString()).isEqualTo("過去の予約はキャンセルできません。");
            assertThat(root.path("instance").asString()).isEqualTo(RESERVATIONS + "/" + target);
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("DEL-08: 他人の過去日の予約には400ではなく403を返す")
        void cannotDeleteOthersPastReservation() throws Exception {
            UUID target = save(D.minusDays(1), 1, B);

            assertRejectedDelete(target.toString(), A, 403);
        }
    }

    private UUID save(LocalDate date, int seat, String user) {
        return reservations.saveAndFlush(new Reservation(date, (short) seat, user)).getId();
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList("select * from reservations order by id");
    }

    private String body(LocalDate date, int seat) {
        return "{\"date\":\"%s\",\"seatNumber\":%d}".formatted(date, seat);
    }

    private HttpResponse<String> request(String method, String path, String user, String body)
            throws Exception {
        Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30));
        if (user != null) {
            builder.header("X-Demo-User", user);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return client.send(
                builder.method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getSeats(String date, String user) throws Exception {
        HttpResponse<String> response = request("GET", "/api/seats?date=" + date, user, null);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return response;
    }

    private HttpResponse<String> post(LocalDate date, int seat, String user) throws Exception {
        return request("POST", RESERVATIONS, user, body(date, seat));
    }

    private HttpResponse<String> delete(String id, String user) throws Exception {
        return request("DELETE", RESERVATIONS + "/" + id, user, null);
    }

    private JsonNode json(HttpResponse<String> response, int status, MediaType type) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body())
                .isEqualTo(status);
        assertThat(MediaType
                .parseMediaType(response.headers().firstValue("Content-Type").orElseThrow())
                .isCompatibleWith(type)).isTrue();
        return mapper.readTree(response.body());
    }

    private JsonNode seats(HttpResponse<String> response) {
        JsonNode root = json(response, 200, MediaType.APPLICATION_JSON);
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

    private UUID assertCreated(HttpResponse<String> response, LocalDate date, int seat,
            String user) {
        JsonNode root = json(response, 201, MediaType.APPLICATION_JSON);
        UUID id = UUID.fromString(root.path("id").asString());
        assertThat(root.path("date").asString()).isEqualTo(date.toString());
        assertThat(root.path("seatNumber").asInt()).isEqualTo(seat);
        assertThat(response.headers().firstValue("Location")).contains(RESERVATIONS + "/" + id);
        Map<String, Object> saved = jdbc.queryForMap("select * from reservations where id = ?", id);
        assertThat(saved.get("reservation_date")).isEqualTo(java.sql.Date.valueOf(date));
        assertThat(((Number) saved.get("seat_number")).intValue()).isEqualTo(seat);
        assertThat(saved.get("user_id")).isEqualTo(user);
        assertThat(saved.get("created_at")).isNotNull();
        return id;
    }

    private void assertProblem(HttpResponse<String> response, int status, String path) {
        JsonNode root = json(response, status, MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(root.path("status").asInt()).isEqualTo(status);
        assertThat(root.path("title").asString())
                .isEqualTo(HttpStatus.valueOf(status).getReasonPhrase());
        assertThat(root.path("instance").asString()).isEqualTo(path);
        String detail = switch (status) {
            case 400 -> "リクエストの内容が不正です。";
            case 403 -> "指定された予約はキャンセルできません。";
            case 404 -> "指定された予約が見つかりません。";
            case 409 -> "指定された日付・座席は予約済みです。";
            default -> throw new IllegalArgumentException("Unexpected status: " + status);
        };
        assertThat(root.path("detail").asString()).isEqualTo(detail);
    }

    private void assertDuplicate(String secondUser) throws Exception {
        assertCreated(post(D, 1, A), D, 1, A);
        List<Map<String, Object>> before = snapshot();
        assertThat(before).hasSize(1);
        assertProblem(post(D, 1, secondUser), 409, RESERVATIONS);
        assertThat(snapshot()).isEqualTo(before);
    }

    private void assertRejectedPost(String body, String user) throws Exception {
        List<Map<String, Object>> before = snapshot();
        assertProblem(request("POST", RESERVATIONS, user, body), 400, RESERVATIONS);
        assertThat(before).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    private void assertRejectedDelete(String id, String user, int status) throws Exception {
        List<Map<String, Object>> before = snapshot();
        assertProblem(delete(id, user), status, RESERVATIONS + "/" + id);
        assertThat(snapshot()).isEqualTo(before);
    }

    private void assertDeleted(HttpResponse<String> response) {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
    }
}
