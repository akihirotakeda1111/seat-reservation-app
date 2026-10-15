package com.example.seat_reservation_api.integration;

import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.repository.ReservationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** DB関連の結合テスト共通関数 */
final class ReservationIntegrationTestSupport {
    private final ReservationRepository reservations;
    private final JdbcTemplate jdbc;

    ReservationIntegrationTestSupport(ReservationRepository reservations, JdbcTemplate jdbc) {
        // 呼び出し元のRepositoryとJDBCを使用し、Transactionの開始・終了はここでは制御しない。
        this.reservations = reservations;
        this.jdbc = jdbc;
    }

    void assertSeatMaster() {
        // マスタ確認：座席番号が1〜30の過不足ない構成であることを確認する。
        assertThat(jdbc.queryForList("select seat_number from seats order by seat_number",
                Integer.class)).containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, 30).boxed().toList());
    }

    UUID save(LocalDate date, int seat, String user) {
        // データ作成：予約を保存してSQLを発行し、採番されたIDを返す。
        // 外側Transactionがある場合、flushだけではcommitされない。
        return reservations.saveAndFlush(new Reservation(date, (short) seat, user)).getId();
    }

    List<Map<String, Object>> snapshot() {
        // 状態取得：比較順をIDで固定し、予約の全項目をDBから取得する。
        // 呼び出し元にTransactionがある場合は、そのTransactionの状態を参照する。
        return jdbc.queryForList("select * from reservations order by id");
    }

    void assertStoredReservation(UUID id, LocalDate date, int seat, String user) {
        // 対象取得：IDに対応する予約を1件取得する。0件・複数件なら取得時に失敗する。
        Map<String, Object> row = jdbc.queryForMap("select * from reservations where id = ?", id);

        // 内容確認：日付・座席・所有者が期待値と一致し、作成日時が設定されている。
        assertThat(row.get("reservation_date")).isEqualTo(java.sql.Date.valueOf(date));
        assertThat(((Number) row.get("seat_number")).intValue()).isEqualTo(seat);
        assertThat(row.get("user_id")).isEqualTo(user);
        assertThat(row.get("created_at")).isNotNull();
    }
}
