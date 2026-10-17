package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.support.JwtAuthenticatedTest;

import com.example.seat_reservation_api.controller.ReservationController;
import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.service.ReservationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.Advised;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * doc/testing/transaction-concurrency-test-matrix.md
 * の実HTTP・実DBによるAPIトランザクションテスト。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "logging.level.org.hibernate.SQL=DEBUG")
@ActiveProfiles({ "local", "test" })
@Import(ReservationIntegrationTestConfig.class)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
public class ReservationTransactionIntegrationTest extends JwtAuthenticatedTest {
    private static final String A = "demo-user-1";
    private static final String B = "demo-user-2";
    private static final String RESERVATIONS = "/api/reservations";
    private static final LocalDate D = LocalDate.now(ReservationIntegrationTestConfig.FIXED_CLOCK);

    @Autowired
    ReservationService service;

    @Autowired
    ReservationRepository reservations;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    DataSource dataSource;

    @PersistenceContext
    EntityManager entityManager;

    @LocalServerPort
    int port;

    private ReservationIntegrationTestSupport support;
    private ReservationHttpTestSupport httpSupport;
    private TransactionTemplate transactions;
    private HttpClient client;
    private List<Map<String, Object>> sentinel;

    @BeforeEach
    void setUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        support = new ReservationIntegrationTestSupport(reservations, jdbc);
        transactions = new TransactionTemplate(transactionManager);
        transactions.setTimeout(20);
        reservations.deleteAllInBatch();
        support.assertSeatMaster();
        support.save(D.plusDays(1), 30, B);
        sentinel = support.snapshot();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        httpSupport = new ReservationHttpTestSupport(mapper, support, client, port);
    }

    @AfterEach
    void tearDown() {
        try {
            if (client != null) {
                client.close();
            }
        } finally {
            reservations.deleteAllInBatch();
        }
    }

    @Nested
    @DisplayName("Transaction境界・正常終了・例外")
    class TransactionOutcomeTests {
        @ParameterizedTest(name = "{displayName} [{0}]")
        @ValueSource(strings = { "reserve", "cancelOwnReservation" })
        @DisplayName("T-01-SVC: 境界・分離レベルの確認")
        void serviceOwnsTransaction(String operation, TestReporter reporter) throws Exception {
            // 準備：取消の場合だけ対象予約を用意し、観測回数・完了状態を初期化する。
            UUID existing = operation.equals("cancelOwnReservation") ? support.save(D, 1, A) : null;
            AtomicInteger observed = new AtomicInteger();
            AtomicInteger completion = new AtomicInteger(-1);
            Advised proxy = (Advised) service;
            assertThat(List.of(proxy.getAdvisors()).stream()
                    .anyMatch(advisor -> advisor.getAdvice() instanceof TransactionInterceptor))
                    .isTrue();

            // 観測処理：Service内部のTransaction・分離レベル・終了通知を記録する。
            MethodInterceptor probe = invocation -> {
                if (!invocation.getMethod().getName().equals(operation)) {
                    return invocation.proceed();
                }
                observed.incrementAndGet();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(jdbc.queryForObject("show transaction_isolation", String.class))
                        .isEqualTo("read committed");
                TransactionSynchronizationManager
                        .registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                completion.set(status);
                            }
                        });
                reporter.publishEntry("transaction", String
                        .valueOf(TransactionSynchronizationManager.getCurrentTransactionName()));
                Object result = invocation.proceed();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                return result;
            };

            // 操作：既存Transactionの内側にAdviceを追加し、外側TransactionなしでServiceを呼ぶ。
            proxy.addAdvice(probe);
            UUID created = null;
            try {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                if (operation.equals("reserve")) {
                    created = service.reserve(D, A, (short) 1);
                } else {
                    service.cancelOwnReservation(existing, A);
                }
            } finally {
                // 後処理：Service呼び出しの成否によらずAdviceを除去する。
                proxy.removeAdvice(probe);
            }

            // 結果確認：1回の観測、commit、呼び出し後のTransaction終了とDB状態を確認する。
            assertThat(observed.get()).isEqualTo(1);
            assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (created != null) {
                support.assertStoredReservation(created, D, 1, A);
                assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
            } else {
                assertThat(support.snapshot()).isEqualTo(sentinel);
            }

            // 境界確認：Controllerの取消とServiceの参照にはTransaction属性がないことを確認する。
            AnnotationTransactionAttributeSource attributes = new AnnotationTransactionAttributeSource();
            assertThat(attributes.getTransactionAttribute(
                    ReservationController.class.getMethod("deleteReservation", UUID.class),
                    ReservationController.class)).isNull();
            assertThat(attributes.getTransactionAttribute(
                    ReservationService.class.getMethod("getReservationById", UUID.class),
                    ReservationService.class)).isNull();
            reporter.publishEntry("boundary",
                    "Service existence/ownership/date checks and deletion run inside cancelOwnReservation; isolation=read committed; completion=COMMITTED");
        }

        @Test
        @DisplayName("T-04-SVC: INSERT後のrollback")
        void rollbackFlushedInsert() throws Exception {
            // 準備：rollbackを起こす例外と、取り消される予約IDの保存先を用意する。
            RuntimeException forced = new RuntimeException("test rollback after INSERT");
            AtomicReference<UUID> rolledBack = new AtomicReference<>();

            // 操作：外側TransactionにServiceを参加させ、INSERT・flush後に例外を送出する。
            Throwable thrown = catchThrowable(() -> transactions.executeWithoutResult(status -> {
                UUID id = service.reserve(D, A, (short) 1);
                rolledBack.set(id);
                entityManager.flush();
                support.assertStoredReservation(id, D, 1, A);
                throw forced;
            }));

            // rollback確認：同じ例外が伝播し、Transaction終了後に追加行が残らない。
            assertThat(thrown).isSameAs(forced);
            assertTransactionEnded();
            assertThat(support.snapshot()).isEqualTo(sentinel);

            // 復旧確認：独立したHTTP POSTで同じ座席を予約できる。
            HttpResponse<String> response = httpSupport.post(D, 1, A);
            UUID id = httpSupport.assertCreated(response, D, 1, A);
            assertThat(id).isNotEqualTo(rolledBack.get());
            assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
        }

        @Test
        @DisplayName("T-05-SVC: DELETE後のrollback")
        void rollbackFlushedDelete() throws Exception {
            // 準備：対象予約と削除前の全項目を保存し、rollback用の例外を用意する。
            UUID id = support.save(D, 1, A);
            List<Map<String, Object>> before = support.snapshot();
            RuntimeException forced = new RuntimeException("test rollback after DELETE");

            // 操作：外側Transactionで削除後、DBから行が消えたことを確認して例外を送出する。
            Throwable thrown = catchThrowable(() -> transactions.executeWithoutResult(status -> {
                service.cancelOwnReservation(id, A);

                // 明示DELETEはService内で発行済み。flush後も同一Transactionの削除状態を確認する。
                entityManager.flush();
                assertThat(support.snapshot()).isEqualTo(sentinel);
                throw forced;
            }));

            // rollback確認：元予約の全項目が復元され、Transactionが終了している。
            assertThat(thrown).isSameAs(forced);
            assertTransactionEnded();
            assertThat(support.snapshot()).isEqualTo(before);

            // 復旧確認：続く独立したHTTP DELETEで正常に取り消せる。
            HttpResponse<String> response = httpSupport.delete(id.toString(), A);
            httpSupport.assertDeleted(response);
            assertThat(support.snapshot()).isEqualTo(sentinel);
        }

        @Test
        @DisplayName("T-06-SVC-FLUSH: 明示flushでのUNIQUE違反")
        void duplicateFailsAtExplicitFlush(TestReporter reporter) {
            // 準備：競合予約とDB変更前の状態、例外発生段階を保存する。
            support.save(D, 1, A);
            List<Map<String, Object>> before = support.snapshot();
            AtomicReference<String> phase = new AtomicReference<>("service.reserve");

            // 操作：重複登録後に明示flushし、例外をTransactionの外で捕捉する。
            Throwable thrown = catchThrowable(() -> transactions.executeWithoutResult(status -> {
                service.reserve(D, B, (short) 1);
                phase.set("explicit flush");
                entityManager.flush();
                phase.set("flush returned");
            }));

            // 結果確認：明示flushで対象UNIQUE違反が起き、元予約だけが全項目維持される。
            assertUniqueViolation(thrown, phase.get(), reporter);
            assertThat(phase.get()).isEqualTo("explicit flush");
            assertTransactionEnded();
            assertThat(support.snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("T-06-SVC-COMMIT: Transaction終了時のUNIQUE違反")
        void duplicateFailsAtTransactionCompletion(TestReporter reporter) {
            // 準備：競合予約とDB変更前の状態、例外発生段階を保存する。
            support.save(D, 1, A);
            List<Map<String, Object>> before = support.snapshot();
            AtomicReference<String> phase = new AtomicReference<>("service.reserve");

            // 操作：明示flushせずTransactionを終了し、例外を外側で捕捉する。
            Throwable thrown = catchThrowable(() -> transactions.executeWithoutResult(status -> {
                service.reserve(D, B, (short) 1);
                phase.set("transaction completion");
            }));

            // 結果確認：対象UNIQUE違反と発生経路を確認し、早期発行とcommit時を区別する。
            assertUniqueViolation(thrown, phase.get(), reporter);
            if (phase.get().equals("transaction completion")) {
                assertThat(stackTrace(thrown)).contains("JpaTransactionManager.doCommit");
            }

            // DB確認：Transactionが終了し、既存予約が全項目維持される。
            assertTransactionEnded();
            assertThat(support.snapshot()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("予約登録")
    class CreateReservationTests {
        @Test
        @DisplayName("R-01-DB: 同一ユーザーの先行commitで後続INSERTが一意違反")
        void sameUserCannotInsertAfterCommit(TestReporter reporter) throws Exception {
            // 共通処理：同一ユーザーの後続INSERTを待機させ、先行commit後の一意違反を確認する。
            assertInsertCompetition(A, true, reporter);
        }

        @Test
        @DisplayName("R-02-DB: 同一ユーザーの先行rollbackで後続INSERTが成功")
        void sameUserCanInsertAfterRollback(TestReporter reporter) throws Exception {
            // 共通処理：同一ユーザーの後続INSERTを待機させ、先行rollback後の成功を確認する。
            assertInsertCompetition(A, false, reporter);
        }

        @Test
        @DisplayName("R-03-DB: 異なるユーザーの先行commitで後続INSERTが一意違反")
        void differentUserCannotInsertAfterCommit(TestReporter reporter) throws Exception {
            // 共通処理：別ユーザーBのINSERTを待機させ、Aのcommit後の一意違反を確認する。
            assertInsertCompetition(B, true, reporter);
        }

        @Test
        @DisplayName("R-04-DB: 異なるユーザーの先行rollbackで後続INSERTが成功")
        void differentUserCanInsertAfterRollback(TestReporter reporter) throws Exception {
            // 共通処理：別ユーザーBのINSERTを待機させ、Aのrollback後の成功を確認する。
            assertInsertCompetition(B, false, reporter);
        }

        @Test
        @DisplayName("R-06-DB: 異なる10ユーザーの同時INSERTは1件だけcommit")
        void onlyOneOfTenUsersCanCommit(TestReporter reporter) throws Exception {
            // 準備：10接続を並行実行するWorkerと、準備・開始の同期を用意する。
            var executor = Executors.newFixedThreadPool(10);
            CountDownLatch ready = new CountDownLatch(10);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<InsertResult>> futures = new ArrayList<>();
            List<InsertResult> results = new ArrayList<>();
            try {
                // Worker準備：異なるID・ユーザーで接続し、開始通知後にINSERTとcommit／rollbackを行う。
                for (int i = 1; i <= 10; i++) {
                    String user = "db-user-" + i;
                    UUID id = UUID.randomUUID();
                    futures.add(executor.submit(() -> {
                        try (Connection connection = dataSource.getConnection()) {
                            configureTransaction(connection);
                            try {
                                ready.countDown();
                                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                                return insertAndComplete(connection, id, user);
                            } finally {
                                connection.rollback();
                            }
                        }
                    }));
                }

                // 開始・回収：全接続の準備後に解放し、全結果を期限付きで回収する。
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                for (Future<InsertResult> future : futures) {
                    results.add(future.get(Math.max(1, deadline - System.nanoTime()),
                            TimeUnit.NANOSECONDS));
                }
            } finally {
                start.countDown();

                // 後処理：未完了のFutureを取り消し、全Workerを終了させる。
                futures.forEach(future -> future.cancel(true));
                executor.shutdownNow();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            // 結果確認：commit成功1件・対象UNIQUE違反9件と、勝者の予約・監視用予約を確認する。
            results.forEach(result -> reportInsert(result, reporter));
            List<InsertResult> winners = results.stream().filter(r -> r.failure() == null).toList();
            assertThat(winners).hasSize(1);
            List<InsertResult> losers = results.stream().filter(r -> r.failure() != null).toList();
            assertThat(losers).hasSize(9);
            losers.forEach(result -> assertSqlUniqueViolation(result.failure()));
            InsertResult winner = winners.getFirst();
            support.assertStoredReservation(winner.id(), D, 1, winner.user());
            assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
        }
    }

    @Nested
    @DisplayName("キャンセル")
    class CancelReservationTests {
        @Test
        @DisplayName("C-01-DB: 先行DELETEのcommit後は後続DELETEの更新件数0")
        void followerDeletesNothingAfterCommit(TestReporter reporter) throws Exception {
            // 共通処理：後続DELETEの待機を確認し、先行commit後に更新0件となることを検証する。
            assertDeleteCompetition(true, reporter);
        }

        @Test
        @DisplayName("C-02-DB: 先行DELETEのrollback後は後続DELETEの更新件数1")
        void followerDeletesReservationAfterRollback(TestReporter reporter) throws Exception {
            // 共通処理：後続DELETEの待機を確認し、先行rollback後に更新1件となることを検証する。
            assertDeleteCompetition(false, reporter);
        }

        @Test
        @DisplayName("C-02-HYBRID: 先行DELETEのrollback後に所有者のAPI取消が成功")
        void ownerCanCancelAfterRollback(TestReporter reporter) throws Exception {
            // 準備：commit済み予約と、後続HTTPを実行するWorkerを用意する。
            UUID id = support.save(D, 1, A);
            var executor = Executors.newSingleThreadExecutor();
            HttpResponse<String> response;
            try (Connection first = dataSource.getConnection()) {
                configureTransaction(first);
                try {
                    // 先行操作：独立DB接続でDELETEを発行し、未commitのまま保持する。
                    int firstPid = backendPid(first);
                    assertThat(delete(first, id)).isEqualTo(1);

                    // 後続操作：所有者のHTTP DELETEを開始し、実SQLのロック待機を確認する。
                    Future<HttpResponse<String>> future = executor.submit(() -> httpSupport.delete(id.toString(), A));
                    awaitHttpDeleteBlockedBy(firstPid, reporter);
                    assertThat(future.isDone()).isFalse();

                    // 先行解放：rollbackで予約を戻し、後続HTTPの完了を待つ。
                    first.rollback();
                    reporter.publishEntry("T1",
                            "DELETE rows=1; rollback after HTTP DELETE SQL wait");
                    response = future.get(30, TimeUnit.SECONDS);
                } finally {
                    first.rollback();

                    // 後処理：finallyでも先行ロックを解放したうえでWorker終了を待つ。
                    executor.shutdown();
                    assertThat(executor.awaitTermination(35, TimeUnit.SECONDS)).isTrue();
                }
            } finally {
                executor.shutdownNow();
            }

            // 結果確認：後続が204で削除を確定し、監視用予約だけが残る。
            reporter.publishEntry("HTTP", response.statusCode() + " " + response.body());
            httpSupport.assertDeleted(response);
            assertThat(support.snapshot()).isEqualTo(sentinel);
        }

        @Test
        @DisplayName("C-04-HYBRID: 非所有者は未確定DELETE中も403となりrollback後の予約を維持")
        void nonOwnerCannotCancelDuringPendingDelete(TestReporter reporter) throws Exception {
            // 準備：Aの予約と、rollback後に維持すべきDB全項目を保存する。
            UUID id = support.save(D, 1, A);
            List<Map<String, Object>> before = support.snapshot();
            try (Connection first = dataSource.getConnection()) {
                configureTransaction(first);
                try {
                    // 先行操作：Aの予約を未commitで削除した状態にする。
                    assertThat(delete(first, id)).isEqualTo(1);

                    // 後続操作・確認：先行を解放せずBのHTTPを完了させ、403を検証する。
                    HttpResponse<String> response = httpSupport.delete(id.toString(), B);
                    reporter.publishEntry("HTTP before T1 rollback",
                            response.statusCode() + " " + response.body());
                    httpSupport.assertProblem(response, 403, RESERVATIONS + "/" + id,
                            "指定された予約はキャンセルできません。");
                } finally {
                    // 後処理：先行DELETEを取り消し、元予約を復元する。
                    first.rollback();
                    reporter.publishEntry("T1", "DELETE rows=1; rollback");
                }
            }

            // 最終確認：元予約と監視用予約の全項目が維持される。
            assertThat(support.snapshot()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("キャンセルと再予約")
    class CancelAndRebookTests {
        @Test
        @DisplayName("M-01-DB: 先行DELETEのcommit後に別ユーザーの再予約が成功")
        void rebookAfterDeleteCommit(TestReporter reporter) throws Exception {
            // 共通処理：DELETEで待機中の再予約が、先行commit後に新IDで成功することを確認する。
            assertRebookingAfterDelete(true, reporter);
        }

        @Test
        @DisplayName("M-02-DB: 先行DELETEのrollback後は再予約が一意違反となり元予約を維持")
        void preserveReservationAfterDeleteRollback(TestReporter reporter) throws Exception {
            // 共通処理：先行DELETEのrollback後に再予約が一意違反となり、元予約が維持されることを確認する。
            assertRebookingAfterDelete(false, reporter);
        }
    }

    private void assertRebookingAfterDelete(boolean commit, TestReporter reporter)
            throws Exception {
        // 準備：既存予約・再予約ID・変更前DB状態を用意する。
        UUID oldId = support.save(D, 1, A);
        UUID newId = UUID.randomUUID();
        assertThat(newId).isNotEqualTo(oldId);
        List<Map<String, Object>> before = support.snapshot();
        var executor = Executors.newSingleThreadExecutor();
        InsertResult result;

        // 接続準備：独立した2つのTransactionを開始する。
        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            configureTransaction(first);
            configureTransaction(second);
            try {
                int firstPid = backendPid(first);
                int secondPid = backendPid(second);

                // 先行操作：DELETEを未commitで保持してから、後続INSERTを開始する。
                assertThat(delete(first, oldId)).isEqualTo(1);
                Future<InsertResult> future = executor.submit(() -> insertAndComplete(second, newId, B));

                // 順序制御：後続の実ロック待機を確認し、先行を指定どおりcommit／rollbackする。
                awaitBlockedBy(secondPid, firstPid, reporter);
                assertThat(future.isDone()).isFalse();
                if (commit) {
                    first.commit();
                } else {
                    first.rollback();
                }
                reporter.publishEntry("T1",
                        "DELETE id=" + oldId + "; rows=1; " + (commit ? "commit" : "rollback"));

                // 結果回収：先行解放後のINSERT・Transaction終了を待つ。
                result = future.get(30, TimeUnit.SECONDS);
            } finally {
                first.rollback();

                // 後処理：先行ロックを解放し、Worker終了後に接続を閉じる。
                executor.shutdown();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
                second.rollback();
            }
        } finally {
            executor.shutdownNow();
        }

        // 最終確認：commitならBの新予約、rollbackなら一意違反と元予約の全項目保持を確認する。
        reportInsert(result, reporter);
        if (commit) {
            assertThat((Throwable) result.failure()).isNull();
            support.assertStoredReservation(newId, D, 1, B);
            assertThat(jdbc.queryForObject("select count(*) from reservations where id = ?",
                    Integer.class, oldId)).isZero();
            assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
        } else {
            assertSqlUniqueViolation(result.failure());
            assertThat(support.snapshot()).isEqualTo(before);
        }
    }

    private void assertDeleteCompetition(boolean commit, TestReporter reporter) throws Exception {
        // 準備：commit済みの対象予約と後続Workerを用意する。
        UUID id = support.save(D, 1, A);
        var executor = Executors.newSingleThreadExecutor();
        int deleted;

        // 接続準備：先行と後続を別Transactionとして開始する。
        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            configureTransaction(first);
            configureTransaction(second);
            try {
                int firstPid = backendPid(first);
                int secondPid = backendPid(second);

                // 先行・後続操作：先行DELETEを保持し、後続DELETEを開始する。
                assertThat(delete(first, id)).isEqualTo(1);
                Future<Integer> future = executor.submit(() -> {
                    try {
                        int count = delete(second, id);
                        second.commit();
                        return count;
                    } finally {
                        second.rollback();
                    }
                });

                // 順序制御：実ロック待機後に先行をcommit／rollbackし、後続の更新件数を回収する。
                awaitBlockedBy(secondPid, firstPid, reporter);
                assertThat(future.isDone()).isFalse();
                if (commit) {
                    first.commit();
                } else {
                    first.rollback();
                }
                reporter.publishEntry("T1", "DELETE rows=1; " + (commit ? "commit" : "rollback"));
                deleted = future.get(30, TimeUnit.SECONDS);
            } finally {
                first.rollback();

                // 後処理：先行ロックを解放し、後続終了後に接続を閉じる。
                executor.shutdown();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
                second.rollback();
            }
        } finally {
            executor.shutdownNow();
        }

        // 最終確認：先行commitなら後続0件、rollbackなら1件となり、対象予約は消える。
        reporter.publishEntry("T2", "DELETE rows=" + deleted + "; commit");
        assertThat(deleted).isEqualTo(commit ? 0 : 1);
        assertThat(support.snapshot()).isEqualTo(sentinel);
    }

    private int delete(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("delete from reservations where id = ?")) {
            statement.setObject(1, id);
            return statement.executeUpdate();
        }
    }

    private void awaitHttpDeleteBlockedBy(int blockingPid, TestReporter reporter)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            List<Map<String, Object>> waiting = jdbc.queryForList("""
                    select pid, query, wait_event_type, wait_event
                    from pg_stat_activity
                    where datname = current_database() and state = 'active'
                      and ? = any(pg_blocking_pids(pid))
                      and lower(query) like '%delete from reservations%'
                    """, blockingPid);
            if (!waiting.isEmpty()) {
                assertThat(waiting).hasSize(1);
                reporter.publishEntry("HTTP DELETE wait",
                        "blocked by pid=" + blockingPid + "; " + waiting);
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("HTTP DELETEのDBロック待機を確認できませんでした");
    }

    private void assertInsertCompetition(String followerUser, boolean commit, TestReporter reporter)
            throws Exception {
        // 準備：先行・後続の予約IDと、後続を実行するWorkerを用意する。
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        Future<InsertResult> future = null;
        InsertResult result;

        // 接続準備：同一キーへ操作する2つの独立Transactionを開始する。
        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            configureTransaction(first);
            configureTransaction(second);
            try {
                int firstPid = backendPid(first);
                int secondPid = backendPid(second);

                // 先行・後続操作：先行INSERTを保持してから後続INSERTを開始する。
                insert(first, firstId, A);
                future = executor.submit(() -> insertAndComplete(second, secondId, followerUser));

                // 順序制御：後続の実ロック待機を確認してから先行をcommit／rollbackする。
                awaitBlockedBy(secondPid, firstPid, reporter);
                assertThat(future.isDone()).isFalse();
                if (commit) {
                    first.commit();
                } else {
                    first.rollback();
                }
                reporter.publishEntry("T1", commit ? "commit" : "rollback");

                // 結果回収：後続のINSERTとcommit／rollbackの完了を待つ。
                result = future.get(30, TimeUnit.SECONDS);
            } finally {
                first.rollback();

                // 後処理：先行ロックを解放し、残ったWorkerを終了して接続を閉じる。
                if (future != null) {
                    future.cancel(true);
                }
                executor.shutdownNow();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
                second.rollback();
            }
        } finally {
            executor.shutdownNow();
        }

        // 最終確認：先行commitなら先行予約、rollbackなら後続予約だけが残る。
        reportInsert(result, reporter);
        if (commit) {
            assertSqlUniqueViolation(result.failure());
            support.assertStoredReservation(firstId, D, 1, A);
        } else {
            assertThat((Throwable) result.failure()).isNull();
            support.assertStoredReservation(secondId, D, 1, followerUser);
        }
        assertThat(support.snapshot()).hasSize(2).containsAll(sentinel);
    }

    private void configureTransaction(Connection connection) throws SQLException {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("set local statement_timeout = '20s'");
            statement.execute("set local lock_timeout = '15s'");
            try (var rows = statement.executeQuery("show transaction_isolation")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("read committed");
            }
        }
    }

    private int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("select pg_backend_pid()")) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }

    private void awaitBlockedBy(int waitingPid, int blockingPid, TestReporter reporter)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            Boolean blocked = jdbc.queryForObject("select ? = any(pg_blocking_pids(?))",
                    Boolean.class, blockingPid, waitingPid);
            if (Boolean.TRUE.equals(blocked)) {
                reporter.publishEntry("wait",
                        "pid=" + waitingPid + " blocked by pid=" + blockingPid);
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("後続SQLのDBロック待機を確認できませんでした: " + waitingPid);
    }

    private void insert(Connection connection, UUID id, String user) throws SQLException {
        try (var statement = connection.prepareStatement("""
                insert into reservations (id, reservation_date, seat_number, user_id)
                values (?, ?, 1, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, D);
            statement.setString(3, user);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private InsertResult insertAndComplete(Connection connection, UUID id, String user)
            throws SQLException {
        try {
            insert(connection, id, user);
            connection.commit();
            return new InsertResult(id, user, null);
        } catch (SQLException failure) {
            connection.rollback();
            return new InsertResult(id, user, failure);
        }
    }

    private void assertSqlUniqueViolation(SQLException failure) {
        assertThat((Throwable) failure).isNotNull();
        assertThat(failure.getSQLState()).isEqualTo("23505");
        assertThat(failure.getMessage()).contains("\"uq_reservations_date_seat\"");
    }

    private void reportInsert(InsertResult result, TestReporter reporter) {
        reporter.publishEntry(Map.of("id", result.id().toString(), "user", result.user(), "outcome",
                result.failure() == null ? "commit" : "rollback", "SQLSTATE",
                result.failure() == null ? "none" : result.failure().getSQLState(), "exception",
                result.failure() == null ? "none" : result.failure().toString()));
    }

    private record InsertResult(UUID id, String user, SQLException failure) {
    }

    private void assertTransactionEnded() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
    }

    private void assertUniqueViolation(Throwable thrown, String phase, TestReporter reporter) {
        assertThat(thrown).isNotNull();
        reporter.publishEntry(Map.of("phase", phase, "exception", thrown.getClass().getName(),
                "trace", stackTrace(thrown)));
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof ConstraintViolationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(ConstraintViolationException.class);
        ConstraintViolationException violation = (ConstraintViolationException) cause;
        assertThat(violation.getSQLState()).isEqualTo("23505");
        assertThat(violation.getConstraintName()).isEqualTo("uq_reservations_date_seat");
        assertThat(violation.getSQL()).containsIgnoringCase("insert into reservations");
        reporter.publishEntry(Map.of("sqlState", violation.getSQLState(), "constraint",
                violation.getConstraintName(), "sql", violation.getSQL()));
    }

    private String stackTrace(Throwable thrown) {
        StringWriter trace = new StringWriter();
        thrown.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }

}
