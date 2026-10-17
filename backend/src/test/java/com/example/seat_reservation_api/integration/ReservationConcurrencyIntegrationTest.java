package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.support.JwtAuthenticatedTest;

import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.service.ReservationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.aop.framework.Advised;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Callable;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** doc/testing/transaction-concurrency-test-matrix.md の実HTTP・実DBによるAPI並列テスト。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "logging.level.org.hibernate.SQL=DEBUG")
@ActiveProfiles({ "local", "test" })
@Import({ ReservationIntegrationTestConfig.class,
        ReservationConcurrencyIntegrationTest.IntegrationConfig.class })
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public class ReservationConcurrencyIntegrationTest extends JwtAuthenticatedTest {
    private static final String A = "demo-user-1";
    private static final String B = "demo-user-2";
    private static final String RESERVATIONS = "/api/reservations";
    private static final LocalDate D = LocalDate.now(ReservationIntegrationTestConfig.FIXED_CLOCK);

    @TestConfiguration(proxyBeanMethods = false)
    static class IntegrationConfig {
        @Bean
        static CancellationSynchronization cancellationSynchronization() {
            return new CancellationSynchronization();
        }

        @Bean
        ExceptionObservation exceptionObservation() {
            return new ExceptionObservation();
        }
    }

    static class CancellationSynchronization implements BeanPostProcessor {
        volatile CyclicBarrier checked;
        volatile CountDownLatch nonOwnerRead;

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!(bean instanceof ReservationRepository)) {
                return bean;
            }
            ProxyFactory proxy = new ProxyFactory(bean);
            proxy.setInterfaces(ReservationRepository.class);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                if (invocation.getMethod().getName().equals("deleteReservationById")) {
                    CyclicBarrier barrier = checked;
                    if (barrier != null) {
                        barrier.await(10, TimeUnit.SECONDS);
                    }
                    CountDownLatch read = nonOwnerRead;
                    if (read != null && !read.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Non-owner did not read the reservation");
                    }
                }
                Object result = invocation.proceed();
                CountDownLatch read = nonOwnerRead;
                if (read != null && result instanceof java.util.Optional<?> reservation
                        && reservation.isPresent()
                        && invocation.getMethod().getName().equals("findById")
                        && SecurityContextHolder.getContext().getAuthentication() != null
                        && B.equals(SecurityContextHolder.getContext().getAuthentication().getName())) {
                    read.countDown();
                }
                return result;
            });
            return proxy.getProxy();
        }
    }

    static class ExceptionObservation implements WebMvcConfigurer {
        final AtomicReference<Exception> last = new AtomicReference<>();

        @Override
        public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
            resolvers.add(0, (request, response, handler, exception) -> {
                last.set(exception);
                return null;
            });
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

    @Autowired
    ExceptionObservation observation;

    @Autowired
    CancellationSynchronization cancellationSynchronization;

    @Autowired
    ReservationService service;

    @PersistenceContext
    EntityManager entityManager;

    private HttpClient client;
    private ReservationIntegrationTestSupport support;
    private ReservationHttpTestSupport httpSupport;
    private List<Map<String, Object>> sentinel;

    @BeforeEach
    void setUp() {
        support = new ReservationIntegrationTestSupport(reservations, jdbc);
        observation.last.set(null);
        cancellationSynchronization.checked = null;
        cancellationSynchronization.nonOwnerRead = null;
        reservations.deleteAllInBatch();
        support.assertSeatMaster();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        httpSupport = new ReservationHttpTestSupport(mapper, support, client, port);
        assertThat(jdbc.queryForObject("show transaction_isolation", String.class))
                .isEqualTo("read committed");
        support.save(D.plusDays(1), 30, B);
        sentinel = support.snapshot();
    }

    @AfterEach
    void closeClient() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            try {
                removeInsertFailure();
            } finally {
                reservations.deleteAllInBatch();
            }
        }
    }

    @Nested
    @DisplayName("Transaction境界・正常終了・例外")
    class TransactionOutcomeTests {
        @Test
        @DisplayName("T-02-API: 予約成功のcommit")
        void reservationIsCommittedBeforeCreatedResponse() throws Exception {
            // 操作・応答確認：POST成功と、応答IDに対応するDB予約を検証する。
            httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);

            // 最終状態：監視用予約を保持し、想定外の例外がないことを確認する。
            assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
            assertThat(observation.last.get()).isNull();
        }

        @Test
        @DisplayName("T-03-API: キャンセル成功のcommit")
        void cancellationIsCommittedBeforeNoContentResponse() throws Exception {
            // 準備：所有者Aの予約をcommit済みで用意する。
            UUID id = support.save(D, 1, A);

            // 操作：所有者がHTTP DELETEを実行する。
            HttpResponse<String> response = httpSupport.delete(id.toString(), A);

            // 結果確認：204・本文なしと、対象予約の削除確定を検証する。
            httpSupport.assertDeleted(response);
            assertThat(support.snapshot()).isEqualTo(sentinel);
            assertThat(observation.last.get()).isNull();
        }

        @Test
        @DisplayName("T-06-API: 通常POSTのUNIQUE違反")
        void duplicateAtCommitBecomesConflict(TestReporter reporter) throws Exception {
            // 準備：競合対象の予約と、変更前のDB全項目を保存する。
            support.save(D, 1, A);
            List<Map<String, Object>> before = support.snapshot();

            // 操作・応答確認：同じ日付・座席へのPOSTが409となることを検証する。
            httpSupport.assertProblem(httpSupport.post(D, 1, B), 409, RESERVATIONS,
                    "指定された日付・座席は予約済みです。");

            // 結果確認：元予約を保持し、commit経路の対象UNIQUE違反を確認する。
            assertThat(support.snapshot()).isEqualTo(before);
            assertDatabaseFailure("23505", "uq_reservations_date_seat", reporter);
        }

        @Test
        @DisplayName("T-07-API: UNIQUE以外のDBエラー")
        void otherDatabaseFailureRollsBackAndRecovers(TestReporter reporter) throws Exception {
            // 準備：対象キーのINSERTだけにCHECK違反を注入する。
            try {
                jdbc.execute("""
                        CREATE FUNCTION test_fail_reservation_insert() RETURNS trigger
                        LANGUAGE plpgsql AS $$
                        BEGIN
                            IF NEW.seat_number = 1 AND NEW.reservation_date = DATE '%s' THEN
                                RAISE EXCEPTION 'injected reservation check failure'
                                    USING ERRCODE = '23514', CONSTRAINT = 'test_reservation_check';
                            END IF;
                            RETURN NEW;
                        END;
                        $$
                        """.formatted(D));
                jdbc.execute("""
                        CREATE TRIGGER test_reservation_insert_failure
                        BEFORE INSERT ON reservations
                        FOR EACH ROW EXECUTE FUNCTION test_fail_reservation_insert()
                        """);

                // 異常系確認：500への変換、追加行のrollback、原因例外を確認する。
                httpSupport.assertProblem(httpSupport.post(D, 1, A), 500, RESERVATIONS,
                        "システムエラーが発生しました。");
                assertThat(support.snapshot()).isEqualTo(sentinel);
                assertDatabaseFailure("23514", null, reporter);
            } finally {
                // 後処理：成否にかかわらず、注入したトリガーと関数を除去する。
                removeInsertFailure();
            }

            // 復旧確認：障害解除後のPOSTが成功し、監視用予約も保持される。
            observation.last.set(null);
            httpSupport.assertCreated(httpSupport.post(D, 1, A), D, 1, A);
            assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
            assertThat(observation.last.get()).isNull();
        }
    }

    @Nested
    @DisplayName("予約登録")
    class CreateReservationTests {
        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("R-01-API: 同一ユーザー・同一座席への同時POST")
        void concurrentReservationsBySameUser(TestReporter reporter) throws Exception {
            // 操作・検証を共通処理へ委譲：同じユーザーの2件を競合させ、成功1件・競合1件を確認する。
            assertConcurrentReservations(List.of(A, A), reporter);
        }

        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("R-03-API: 異なるユーザー・同一座席への同時POST")
        void concurrentReservationsByDifferentUsers(TestReporter reporter) throws Exception {
            // 操作・検証を共通処理へ委譲：A/Bを競合させ、勝者の予約だけが残ることを確認する。
            assertConcurrentReservations(List.of(A, B), reporter);
        }

        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("R-05-API: 同一座席へ10件の同時POST")
        void tenConcurrentReservations(TestReporter reporter) throws Exception {
            // 操作・検証を共通処理へ委譲：A/B各5件を競合させ、201が1件・409が9件となることを確認する。
            assertConcurrentReservations(List.of(A, B, A, B, A, B, A, B, A, B), reporter);
        }
    }

    @Nested
    @DisplayName("キャンセル")
    class CancelReservationTests {
        @RepeatedTest(value = 10, name = "{displayName}[{currentRepetition}/{totalRepetitions}]")
        @DisplayName("C-01-API: 同一予約への自由な同時DELETE")
        void concurrentCancellation(TestReporter reporter) throws Exception {
            // 準備：同時取消の対象予約を用意する。
            UUID id = support.save(D, 1, A);

            // 操作・検証：開始のみ同期し、204と404、対象予約の消失を確認する。
            assertCompetingCancellations(id, reporter);
        }

        @RepeatedTest(value = 10, name = "{displayName}[{currentRepetition}/{totalRepetitions}]")
        @DisplayName("C-01-API-CHECKED: 両方の存在確認後にDELETEが競合")
        void cancellationAfterBothRequestsChecked(TestReporter reporter) throws Exception {
            // 準備：両リクエストを存在・権限確認後に合流させる同期条件を設定する。
            UUID id = support.save(D, 1, A);
            cancellationSynchronization.checked = new CyclicBarrier(2);
            try {
                // 操作・検証：両方の確認通過後に削除を競合させる。
                assertCompetingCancellations(id, reporter);
            } finally {
                // 後処理：次ケースに同期条件を持ち越さない。
                cancellationSynchronization.checked = null;
            }
        }

        @ParameterizedTest(name = "{displayName} [{0}, repetition={1}]")
        @MethodSource("com.example.seat_reservation_api.integration.ReservationConcurrencyIntegrationTest#cancellationOrders")
        @DisplayName("C-03-API: 異なるユーザー・所有者がcommit")
        void nonOwnerCannotCancel(String order, int repetition, TestReporter reporter)
                throws Exception {
            // 準備：Aの予約を作り、引数で指定した順序条件を適用する。
            UUID id = support.save(D, 1, A);
            List<HttpResponse<String>> responses;
            if (order.equals("AFTER_COMMIT")) {
                // 順次実行：Aの204受信後にBを送信し、削除済みの状態を参照させる。
                responses = List.of(httpSupport.delete(id.toString(), A),
                        httpSupport.delete(id.toString(), B));
            } else {
                // 順序制御：BEFORE_COMMITだけBの行取得までAの削除を保留する。
                if (order.equals("BEFORE_COMMIT")) {
                    cancellationSynchronization.nonOwnerRead = new CountDownLatch(1);
                }
                try {
                    // 並列実行：FREEは開始同期のみ、BEFORE_COMMITは設定した保留も適用する。
                    responses = concurrentRequests(List.of(() -> httpSupport.delete(id.toString(), A),
                            () -> httpSupport.delete(id.toString(), B)));
                    if (order.equals("BEFORE_COMMIT")) {
                        assertThat(cancellationSynchronization.nonOwnerRead.getCount()).isZero();
                    }
                } finally {
                    // 後処理：取得待ちの同期条件を解除する。
                    cancellationSynchronization.nonOwnerRead = null;
                }
            }

            // 結果確認：所有者の204と、取得順序に応じた非所有者の403／404を検証する。
            reportCancellation(responses, reporter);
            assertThat(support.snapshot()).isEqualTo(sentinel);
            httpSupport.assertDeleted(responses.get(0));
            int status = responses.get(1).statusCode();
            if (order.equals("FREE")) {
                assertThat(status).isIn(403, 404);
            } else {
                assertThat(status).isEqualTo(order.equals("BEFORE_COMMIT") ? 403 : 404);
            }
            httpSupport.assertProblem(responses.get(1), status, RESERVATIONS + "/" + id,
                    status == 403 ? "指定された予約はキャンセルできません。" : "指定された予約が見つかりません。");
        }

        @Test
        @DisplayName("C-05-API: 異なるユーザー・非所有者が先行")
        void rejectedCancellationDoesNotPreventOwnerCancellation() throws Exception {
            // 準備：所有者Aの予約と、拒否前のDB状態を保存する。
            UUID id = support.save(D, 1, A);
            List<Map<String, Object>> before = support.snapshot();

            // 非所有者の操作：Bは403となり、予約の全項目が維持される。
            httpSupport.assertProblem(httpSupport.delete(id.toString(), B), 403,
                    RESERVATIONS + "/" + id, "指定された予約はキャンセルできません。");
            assertThat(support.snapshot()).isEqualTo(before);

            // 所有者の操作：続くAの取消は204となり、対象予約だけが消える。
            httpSupport.assertDeleted(httpSupport.delete(id.toString(), A));
            assertThat(support.snapshot()).isEqualTo(sentinel);
        }
    }

    @Nested
    @DisplayName("異なるリソース")
    class IndependentResourceTests {
        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("I-01-API: 同一日付・異なる座席")
        void reserveDifferentSeatsOnSameDate(TestReporter reporter) throws Exception {
            // 事前確認：対象は空席で、監視用予約だけが存在する。
            assertThat(support.snapshot()).isEqualTo(sentinel);

            // 並列操作：同じ日付の異なる座席をA/Bが予約する。
            List<HttpResponse<String>> responses = concurrentRequests(
                    List.of(() -> httpSupport.post(D, 1, A), () -> httpSupport.post(D, 2, B)));

            // 結果確認：両方201、異なるID、各予約の内容と監視用予約の保持を確認する。
            reportIndependentResponses(responses, reporter);
            UUID first = httpSupport.assertCreated(responses.get(0), D, 1, A);
            UUID second = httpSupport.assertCreated(responses.get(1), D, 2, B);
            assertThat(second).isNotEqualTo(first);
            assertThat(support.snapshot()).hasSize(3).containsAll(sentinel);
            assertThat(observation.last.get()).isNull();
        }

        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("I-02-API: 同一座席・異なる日付")
        void reserveSameSeatOnDifferentDates(TestReporter reporter) throws Exception {
            // 事前確認：対象は空席で、監視用予約だけが存在する。
            assertThat(support.snapshot()).isEqualTo(sentinel);

            // 並列操作：同じ座席の異なる日付をA/Bが予約する。
            List<HttpResponse<String>> responses = concurrentRequests(List.of(() -> httpSupport.post(D, 1, A),
                    () -> httpSupport.post(D.plusDays(1), 1, B)));

            // 結果確認：両方201、異なるID、各日付の予約と監視用予約の保持を確認する。
            reportIndependentResponses(responses, reporter);
            UUID first = httpSupport.assertCreated(responses.get(0), D, 1, A);
            UUID second = httpSupport.assertCreated(responses.get(1), D.plusDays(1), 1, B);
            assertThat(second).isNotEqualTo(first);
            assertThat(support.snapshot()).hasSize(3).containsAll(sentinel);
            assertThat(observation.last.get()).isNull();
        }

        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("I-03-API: 異なる予約の同時キャンセル")
        void cancelDifferentReservations(TestReporter reporter) throws Exception {
            // 準備：A/Bがそれぞれ所有する別座席の予約を用意する。
            UUID first = support.save(D, 1, A);
            UUID second = support.save(D, 2, B);
            assertThat(support.snapshot()).hasSize(3).containsAll(sentinel);

            // 並列操作：各所有者が自分の予約を取り消す。
            List<HttpResponse<String>> responses = concurrentRequests(
                    List.of(() -> httpSupport.delete(first.toString(), A),
                            () -> httpSupport.delete(second.toString(), B)));

            // 結果確認：両方204となり、監視用予約だけが残る。
            reportIndependentResponses(responses, reporter);
            responses.forEach(httpSupport::assertDeleted);
            assertThat(support.snapshot()).isEqualTo(sentinel);
            assertThat(observation.last.get()).isNull();
        }
    }

    @Nested
    @DisplayName("異なるリソース：先行Transaction保留中の完了")
    class HeldIndependentResourceTests {
        @Test
        @DisplayName("I-01-API-HELD: 同日・別座席は先行commitを待たず予約完了")
        void reserveDifferentSeatWhileFirstIsHeld(TestReporter reporter) throws Exception {
            // 操作・検証を共通処理へ委譲：Aの未commit中に、別座席のBが完了することを確認する。
            assertIndependentReservationWhileHeld(D, 2, reporter);
        }

        @Test
        @DisplayName("I-02-API-HELD: 別日・同一座席は先行commitを待たず予約完了")
        void reserveDifferentDateWhileFirstIsHeld(TestReporter reporter) throws Exception {
            // 操作・検証を共通処理へ委譲：Aの未commit中に、別日付のBが完了することを確認する。
            assertIndependentReservationWhileHeld(D.plusDays(1), 1, reporter);
        }

        @Test
        @DisplayName("I-03-API-HELD: 別予約の取消は先行commitを待たず完了")
        void cancelDifferentReservationWhileFirstIsHeld(TestReporter reporter) throws Exception {
            // 準備：別予約を用意し、Bだけ削除済みとなる保留中の期待状態を保存する。
            UUID first = support.save(D, 1, A);
            UUID second = support.save(D, 2, B);
            List<Map<String, Object>> whileHeld = support.snapshot().stream()
                    .filter(row -> !second.equals(row.get("id"))).toList();

            // 制御付き操作：AのDELETEを保留し、Bの204とcommit済みのDB状態を先に確認する。
            runWhileFirstHeld("cancelOwnReservation",
                    () -> httpSupport.delete(first.toString(), A),
                    () -> httpSupport.delete(second.toString(), B),
                    response -> {
                        httpSupport.assertDeleted(response);
                        // AのDELETEは未commitなので、観測接続には元の行が全項目見える。
                        assertThat(support.snapshot()).isEqualTo(whileHeld);
                    }, httpSupport::assertDeleted, reporter);

            // 最終確認：A解放後は両予約が消え、監視用予約だけが残る。
            assertThat(support.snapshot()).isEqualTo(sentinel);
        }
    }

    private void assertIndependentReservationWhileHeld(LocalDate secondDate, int secondSeat,
            TestReporter reporter) throws Exception {
        // 制御付き操作：AのINSERTを保留したままBのPOSTを完了させる。
        runWhileFirstHeld("reserve", () -> httpSupport.post(D, 1, A),
                () -> httpSupport.post(secondDate, secondSeat, B),
                response -> {
                    httpSupport.assertCreated(response, secondDate, secondSeat, B);
                    // Bは別接続から参照可能、未commitのAはまだ参照できない。
                    assertThat(jdbc.queryForObject("""
                            select count(*) from reservations
                            where reservation_date = ? and seat_number = 1
                            """, Integer.class, D)).isZero();
                    assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
                }, response -> httpSupport.assertCreated(response, D, 1, A), reporter);

        // 最終確認：A解放後は両予約と監視用予約が残る。
        assertThat(support.snapshot()).hasSize(3).containsAll(sentinel);
    }

    private void runWhileFirstHeld(String operation, Callable<HttpResponse<String>> firstCall,
            Callable<HttpResponse<String>> secondCall, Consumer<HttpResponse<String>> whileHeld,
            Consumer<HttpResponse<String>> afterRelease, TestReporter reporter) throws Exception {
        // 準備：実Transactionの内側へ観測Adviceを追加できるプロキシを探す。
        // Serviceのプロキシを辿り、実Transactionの内側へ追加する。
        Advised transactional = (Advised) service;
        while (List.of(transactional.getAdvisors()).stream()
                .noneMatch(advisor -> advisor.getAdvice() instanceof TransactionInterceptor)) {
            assertThat(transactional.getTargetSource().isStatic()).isTrue();
            Object target = transactional.getTargetSource().getTarget();
            assertThat(target).isInstanceOf(Advised.class);
            transactional = (Advised) target;
        }

        // 同期状態：SQL発行通知・解放指示・接続PID・Transaction完了状態を管理する。
        CountDownLatch sqlExecuted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();
        AtomicInteger completion = new AtomicInteger(-1);
        AtomicInteger firstObserved = new AtomicInteger();

        // 観測処理：対象Service呼び出しのTransactionと接続を確認する。
        MethodInterceptor probe = invocation -> {
            if (!operation.equals(invocation.getMethod().getName())) {
                return invocation.proceed();
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(jdbc.queryForObject("show transaction_isolation", String.class))
                    .isEqualTo("read committed");
            String user = SecurityContextHolder.getContext().getAuthentication().getName();
            int pid = jdbc.queryForObject("select pg_backend_pid()", Integer.class);

            // 後続B：接続PIDだけ記録し、保留せず実処理を進める。
            if (!A.equals(user)) {
                assertThat(user).isEqualTo(B);
                secondPid.set(pid);
                return invocation.proceed();
            }
            assertThat(firstObserved.incrementAndGet()).isEqualTo(1);
            firstPid.set(pid);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    completion.set(status);
                }
            });

            // 先行A：実処理とflush後、同一TransactionでSQL反映を確認して保留する。
            Object result = invocation.proceed();
            entityManager.flush();
            UUID id = operation.equals("reserve") ? (UUID) result : (UUID) invocation.getArguments()[0];
            assertThat(jdbc.queryForObject("select count(*) from reservations where id = ?",
                    Integer.class, id)).isEqualTo(operation.equals("reserve") ? 1 : 0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            sqlExecuted.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("先行Transactionの解放が期限内に行われませんでした");
            }
            return result;
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        transactional.addAdvice(probe);
        try {
            // 先行開始：AがSQL発行後の保留地点へ到達するまで待つ。
            Future<HttpResponse<String>> first = executor.submit(firstCall);
            assertThat(sqlExecuted.await(10, TimeUnit.SECONDS)).as("先行SQLの発行と保留").isTrue();
            assertThat(first.isDone()).isFalse();

            // 後続開始：Aを解放する前にBの応答を期限付きで回収する。
            Future<HttpResponse<String>> second = executor.submit(secondCall);
            HttpResponse<String> secondResponse;
            try {
                secondResponse = second.get(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
                // 異常時の記録：DBの待機状態を採取し、タイムアウト等の原因調査に使う。
                reporter.publishEntry("wait diagnostics", jdbc.queryForList("""
                        select pid, state, wait_event_type, wait_event, query,
                               pg_blocking_pids(pid) as blockers
                        from pg_stat_activity where datname = current_database()
                        """).toString());
                throw failure;
            }

            // 独立性確認：別接続のBが完了しても、Aは有効な未commit状態で保留されている。
            assertThat(secondPid.get()).isPositive().isNotEqualTo(firstPid.get());
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(completion.get()).as("B完了時もAは未commit").isEqualTo(-1);
            assertThat(first.isDone()).isFalse();
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                    where pid = ? and state = 'idle in transaction' and backend_xid is not null
                    """, Integer.class, firstPid.get())).isEqualTo(1);

            // 保留中のDB確認：別接続からBの確定結果とAの未確定状態を照合する。
            whileHeld.accept(secondResponse);
            assertThat(completion.get()).isEqualTo(-1);
            reporter.publishEntry("order", "A SQL held (pid=" + firstPid.get()
                    + ") -> B committed (pid=" + secondPid.get() + ", HTTP="
                    + secondResponse.statusCode() + ") -> release A");

            // 先行解放：Bの確認後にAを解放し、commitと成功応答を確認する。
            release.countDown();
            HttpResponse<String> firstResponse = first.get(30, TimeUnit.SECONDS);
            assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
            afterRelease.accept(firstResponse);
            assertThat(observation.last.get()).isNull();
        } finally {
            release.countDown();

            // 後処理：finallyでも解放し、Worker終了後に観測Adviceを除去する。
            executor.shutdown();
            try {
                assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).as("HTTP Worker終了").isTrue();
            } finally {
                executor.shutdownNow();
                transactional.removeAdvice(probe);
            }
        }
    }

    @Nested
    @DisplayName("キャンセルと再予約")
    class CancelAndRebookTests {
        @Test
        @DisplayName("M-03-API: キャンセル完了後の再予約")
        void rebookAfterCancellationCommits(TestReporter reporter) throws Exception {
            // 準備：再予約対象の座席をAが予約済みの状態にする。
            UUID original = support.save(D, 1, A);

            // 先行操作：Aの取消完了と、対象予約の消失を確認する。
            HttpResponse<String> cancellation = httpSupport.delete(original.toString(), A);
            httpSupport.assertDeleted(cancellation);
            assertThat(support.snapshot()).isEqualTo(sentinel);

            // 後続操作：空いた座席をBが予約し、両HTTP応答を記録する。
            HttpResponse<String> reservation = httpSupport.post(D, 1, B);
            reporter.publishEntry(
                    Map.of("delete", cancellation.statusCode() + " " + cancellation.body(), "post",
                            reservation.statusCode() + " " + reservation.body()));

            // 最終確認：Bの新予約、旧IDの消失、監視用予約の保持を検証する。
            UUID replacement = httpSupport.assertCreated(reservation, D, 1, B);
            assertThat(replacement).isNotEqualTo(original);
            assertThat(jdbc.queryForObject("select count(*) from reservations where id = ?",
                    Long.class, original)).isZero();
            assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
            assertThat(observation.last.get()).isNull();
        }

        @RepeatedTest(value = 10, name = "{displayName} [{currentRepetition}/{totalRepetitions}]")
        @DisplayName("M-04-API: DELETEとPOSTの自由な同時実行")
        void concurrentCancellationAndRebooking(TestReporter reporter) throws Exception {
            // 準備：Aの既存予約を用意する。
            UUID original = support.save(D, 1, A);

            // 並列操作：Aの取消とBの再予約を同時開始し、入力順で応答を回収する。
            List<HttpResponse<String>> responses = concurrentRequests(
                    List.of(() -> httpSupport.delete(original.toString(), A),
                            () -> httpSupport.post(D, 1, B)));
            HttpResponse<String> cancellation = responses.get(0);
            HttpResponse<String> reservation = responses.get(1);
            reporter.publishEntry(
                    Map.of("delete", cancellation.statusCode() + " " + cancellation.body(), "post",
                            reservation.statusCode() + " " + reservation.body()));

            // 共通確認：取消は204、再予約は201または409となり、旧予約は消える。
            httpSupport.assertDeleted(cancellation);
            assertThat(reservation.statusCode()).as(reservation.body()).isIn(201, 409);
            assertThat(jdbc.queryForObject("select count(*) from reservations where id = ?",
                    Long.class, original)).isZero();

            // 分岐確認：201ならBの新予約、409なら対象予約なしのDB状態を検証する。
            if (reservation.statusCode() == 201) {
                UUID replacement = httpSupport.assertCreated(reservation, D, 1, B);
                assertThat(replacement).isNotEqualTo(original);
                assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
            } else {
                httpSupport.assertProblem(reservation, 409, RESERVATIONS, "指定された日付・座席は予約済みです。");
                assertThat(support.snapshot()).isEqualTo(sentinel);
            }
        }
    }

    private void reportIndependentResponses(List<HttpResponse<String>> responses,
            TestReporter reporter) {
        reporter.publishEntry("independentResponses", responses.stream()
                .map(response -> response.request().method() + " " + response.uri() + " user="
                        + jwtDecoder.decode(response.request().headers().firstValue("Authorization")
                                .orElseThrow().substring("Bearer ".length())).getSubject()
                        + " status=" + response.statusCode() + " " + response.body())
                .collect(java.util.stream.Collectors.joining("\n")));
    }

    static Stream<Arguments> cancellationOrders() {
        return Stream.of("FREE", "BEFORE_COMMIT", "AFTER_COMMIT")
                .flatMap(order -> java.util.stream.IntStream.rangeClosed(1, order.equals("FREE") ? 10 : 1)
                        .mapToObj(repetition -> Arguments.of(order, repetition)));
    }

    private void assertCompetingCancellations(UUID id, TestReporter reporter) throws Exception {
        // 並列操作：同一予約への2件のDELETEを同時開始する。
        List<HttpResponse<String>> responses = concurrentRequests(List.of(() -> httpSupport.delete(id.toString(), A),
                () -> httpSupport.delete(id.toString(), A)));

        // 結果確認：予約の消失、204と404の内訳、各応答の形式を検証する。
        reportCancellation(responses, reporter);
        assertThat(support.snapshot()).isEqualTo(sentinel);
        assertThat(responses.stream().map(HttpResponse::statusCode).toList())
                .containsExactlyInAnyOrder(204, 404);
        for (HttpResponse<String> response : responses) {
            if (response.statusCode() == 204) {
                httpSupport.assertDeleted(response);
            } else {
                httpSupport.assertProblem(response, 404, RESERVATIONS + "/" + id,
                        "指定された予約が見つかりません。");
            }
        }
    }

    private void reportCancellation(List<HttpResponse<String>> responses, TestReporter reporter) {
        reporter.publishEntry("cancellationResponses",
                responses.stream().map(response -> response.statusCode() + " " + response.body())
                        .collect(java.util.stream.Collectors.joining("\n")));
        Exception exception = observation.last.get();
        if (exception != null) {
            StringWriter trace = new StringWriter();
            exception.printStackTrace(new PrintWriter(trace));
            reporter.publishEntry("observedException", trace.toString());
        }
    }

    private void assertConcurrentReservations(List<String> users, TestReporter reporter)
            throws Exception {
        // 事前確認：競合対象に予約がないことを確認する。
        assertThat(support.snapshot()).isEqualTo(sentinel);

        // 並列操作：指定ユーザーのPOSTを同時開始し、各応答を記録する。
        List<HttpResponse<String>> responses = concurrentPosts(users);
        reporter.publishEntry("responses",
                java.util.stream.IntStream.range(0, users.size())
                        .mapToObj(i -> users.get(i) + ": " + responses.get(i).statusCode() + " "
                                + responses.get(i).body())
                        .collect(java.util.stream.Collectors.joining("\n")));

        // 応答確認：成功1件・残り409と、勝者の予約内容を検証する。
        assertThat(responses).hasSize(users.size());
        assertThat(responses.stream().filter(response -> response.statusCode() == 201).count())
                .as("successful reservations").isEqualTo(1);
        assertThat(responses.stream().filter(response -> response.statusCode() == 409).count())
                .as("conflicting reservations").isEqualTo(users.size() - 1);
        for (int i = 0; i < responses.size(); i++) {
            HttpResponse<String> response = responses.get(i);
            if (response.statusCode() == 201) {
                httpSupport.assertCreated(response, D, 1, users.get(i));
            } else {
                httpSupport.assertProblem(response, 409, RESERVATIONS, "指定された日付・座席は予約済みです。");
            }
        }

        // DB確認：監視用予約の保持、対象1件、重複キーなしを検証する。
        assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
        assertThat(jdbc.queryForObject("""
                select count(*) from reservations where reservation_date = ? and seat_number = 1
                """, Long.class, D)).isEqualTo(1L);
        assertThat(jdbc.queryForList("""
                select reservation_date, seat_number from reservations
                group by reservation_date, seat_number having count(*) > 1
                """)).isEmpty();
    }

    private List<HttpResponse<String>> concurrentPosts(List<String> users) throws Exception {
        return concurrentRequests(users.stream()
                .<Callable<HttpResponse<String>>>map(user -> () -> httpSupport.post(D, 1, user))
                .toList());
    }

    private List<HttpResponse<String>> concurrentRequests(
            List<Callable<HttpResponse<String>>> requests) throws Exception {
        // 準備：リクエスト数分のWorkerと、準備・開始のラッチを用意する。
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (Callable<HttpResponse<String>> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for HTTP start");
                    }
                    return request.call();
                }));
            }

            // 開始同期：全Workerの準備完了後に送信を解放する。
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all HTTP workers ready").isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            start.countDown();

            // 回収：全体の期限内で入力順に回収する。HTTPの完了順ではない。
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(Math.max(0, deadline - System.nanoTime()),
                        TimeUnit.NANOSECONDS));
            }
            return responses;
        } finally {
            // 後処理：残ったFutureを取り消し、Worker終了を確認する。
            for (Future<HttpResponse<String>> future : futures) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("all HTTP workers terminated").isTrue();
        }
    }

    private void removeInsertFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_reservation_insert_failure ON reservations");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_reservation_insert()");
    }

    private void assertDatabaseFailure(String state, String constraint, TestReporter reporter) {
        Exception exception = observation.last.get();
        assertThat(exception).isInstanceOf(DataIntegrityViolationException.class);
        StringWriter trace = new StringWriter();
        exception.printStackTrace(new PrintWriter(trace));
        reporter.publishEntry("databaseFailure", trace.toString());
        assertThat(trace.toString()).contains("JpaTransactionManager.doCommit");
        Throwable cause = exception;
        while (cause != null && !(cause instanceof ConstraintViolationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(ConstraintViolationException.class);
        ConstraintViolationException violation = (ConstraintViolationException) cause;
        assertThat(violation.getSQLState()).isEqualTo(state);
        if (constraint != null) {
            assertThat(violation.getConstraintName()).isEqualTo(constraint);
        } else {
            assertThat(violation.getConstraintName()).isNotEqualTo("uq_reservations_date_seat");
        }
        assertThat(violation.getSQL()).containsIgnoringCase("insert into reservations");
        reporter.publishEntry(Map.of("sqlState", state, "constraint",
                String.valueOf(violation.getConstraintName()), "exception",
                exception.getClass().getName(), "sql", violation.getSQL()));
    }
}
