package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.repository.ReservationRepository;
import com.example.seat_reservation_api.service.ReservationService;
import com.example.seat_reservation_api.support.JwtAuthenticatedTest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Serviceメソッド認可の検証 */
@SpringBootTest
@ActiveProfiles({"local", "test"})
@Import(ReservationIntegrationTestConfig.class)
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class ReservationMethodSecurityIntegrationTest extends JwtAuthenticatedTest {
    private static final LocalDate D = LocalDate.now(ReservationIntegrationTestConfig.FIXED_CLOCK);

    @Autowired
    ReservationService service;

    @MockitoSpyBean
    ReservationRepository reservations;

    @Autowired
    JdbcTemplate jdbc;

    private ReservationIntegrationTestSupport db;
    private UUID target;

    @BeforeEach
    void setUp() {
        // 準備：予約データを初期化し、座席マスタが揃っていることを確認する。
        db = new ReservationIntegrationTestSupport(reservations, jdbc);
        reservations.deleteAllInBatch();
        db.assertSeatMaster();

        // 事前データ：操作対象と、指定日以外に保持すべき別ユーザーの予約を用意する。
        target = db.save(D, 1, "user-a");
        db.save(D.plusDays(1), 2, "user-b");

        // 観測準備：事前登録の呼び出し履歴を除き、ServiceがSpringプロキシであることを確認する。
        clearInvocations(reservations);
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }

    @Test
    @WithMockUser(username = "user-a", roles = "USER")
    @DisplayName("METHOD-01: 一般ユーザーの管理者一覧取得はRepositoryに到達しない")
    void userCannotRead() {
        // 準備：一般ユーザーの操作前にDB全項目を保存する。
        var before = db.snapshot();

        // 操作・例外確認：URL認可を通さずServiceを呼び、メソッド認可で拒否されることを確認する。
        assertThatThrownBy(() -> service.getAllReservations(D))
                .isInstanceOf(AccessDeniedException.class);

        // 処理到達確認：認可拒否によりRepositoryの検索に進んでいないことを確認する。
        verifyNoInteractions(reservations);

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }

    @Test
    @WithMockUser(username = "user-a", roles = "USER")
    @DisplayName("METHOD-01: 一般ユーザーの管理者取消はRepositoryに到達しない")
    void userCannotCancel() {
        // 準備：一般ユーザー自身の予約でも、管理者用メソッドを呼べないことを検証する。
        var before = db.snapshot();

        // 操作・例外確認：Springプロキシを介した取消がAccessDeniedExceptionになることを確認する。
        assertThatThrownBy(() -> service.cancelReservationAsAdmin(target))
                .isInstanceOf(AccessDeniedException.class);

        // 処理到達確認：予約取得・削除を含めRepositoryを一度も呼んでいないことを確認する。
        verifyNoInteractions(reservations);

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    @DisplayName("METHOD-01: 管理者はServiceの一覧取得を実行できる")
    void adminCanRead() {
        // 準備：管理者による照会前にDB全項目を保存する。
        var before = db.snapshot();

        // 操作・結果確認：管理者はServiceの一覧取得を実行でき、対象日の予約ID・所有者が一致する。
        var result = service.getAllReservations(D);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getId()).isEqualTo(target);
        assertThat(result.getFirst().getUserId()).isEqualTo("user-a");

        // 処理到達確認：認可を通過し、指定日のRepository検索が実行されたことを確認する。
        verify(reservations).findByReservationDate(D);

        // DB確認：操作後も既存予約の全項目が維持されることを確認する。
        assertThat(db.snapshot()).isEqualTo(before);
    }

    @Test
    @WithMockUser(username = "admin-user", roles = "ADMIN")
    @DisplayName("METHOD-01: 管理者はServiceで他人の予約を取り消せる")
    void adminCanCancel() {
        // 準備：取消対象を除いた、保持すべき予約の全項目を保存する。
        var remaining = db.snapshot().stream()
                .filter(row -> !target.equals(row.get("id"))).toList();

        // 操作：管理者がServiceプロキシ経由で他人の予約を取り消す。
        service.cancelReservationAsAdmin(target);

        // 処理到達確認：対象予約の取得とDELETEが実行されたことを確認する。
        verify(reservations).findById(target);
        verify(reservations).deleteReservationById(target);

        // DB確認：取消対象だけが消え、別日の予約は全項目が維持される。
        assertThat(db.snapshot()).hasSize(1).isEqualTo(remaining);
    }
}
