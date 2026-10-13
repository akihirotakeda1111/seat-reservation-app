package com.example.seat_reservation_api.controller;

import com.example.seat_reservation_api.component.DemoCurrentUserProvider;
import com.example.seat_reservation_api.config.ValidationConfig;
import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.exception.GlobalExceptionHandler;
import com.example.seat_reservation_api.exception.InvalidSeatException;
import com.example.seat_reservation_api.filter.SeatCacheControlFilter;
import com.example.seat_reservation_api.repository.SeatReservationView;
import com.example.seat_reservation_api.service.ReservationService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.hibernate.exception.ConstraintViolationException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReservationController.class)
@ActiveProfiles("local")
@Import({GlobalExceptionHandler.class, SeatCacheControlFilter.class, DemoCurrentUserProvider.class,
                ValidationConfig.class, ReservationControllerTest.FixedClockConfig.class})
class ReservationControllerTest {

        @Autowired
        MockMvc mockMvc;

        @MockitoBean
        ReservationService service;

        private static final Clock FIXED_CLOCK =
                        Clock.fixed(Instant.parse("2026-10-09T15:30:00Z"), ZoneId.of("Asia/Tokyo"));

        @TestConfiguration(proxyBeanMethods = false)
        static class FixedClockConfig {

                @Bean
                Clock clock() {
                        return FIXED_CLOCK;
                }
        }

        @Nested
        class GetSeatsTests {
                @Test
                void 空席を返す() throws Exception {
                        String apiPath = "/api/seats";
                        String cacheControlHeader = "no-store";
                        String status = "AVAILABLE";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).plusDays(1);
                        Short seatNumber = 1;
                        String userId = "demo-user-1";

                        SeatReservationView row = mock(SeatReservationView.class);
                        when(row.getSeatNumber()).thenReturn(seatNumber);
                        when(service.getSeatsWithReservation(date)).thenReturn(List.of(row));

                        mockMvc.perform(get(apiPath).param("date", date.toString())
                                        .header("X-Demo-User", userId)).andExpect(status().isOk())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_JSON))
                                        .andExpect(header().string("Cache-Control",
                                                        cacheControlHeader))
                                        .andExpect(jsonPath("$.date").value(date.toString()))
                                        .andExpect(jsonPath("$.seats.length()").value(1))
                                        .andExpect(jsonPath("$.seats[0].seatNumber")
                                                        .value(seatNumber.intValue()))
                                        .andExpect(jsonPath("$.seats[0].status").value(status))
                                        .andExpect(jsonPath("$.seats[0].reservationId")
                                                        .doesNotHaveJsonPath());

                        verify(service).getSeatsWithReservation(date);
                }

                @Test
                void 自分の予約IDのみ返す() throws Exception {
                        LocalDate date = LocalDate.now(FIXED_CLOCK);
                        String currentUserId = "demo-user-1";
                        UUID myReservationId = UUID.randomUUID();
                        UUID otherReservationId = UUID.randomUUID();

                        Reservation myReservation = mock(Reservation.class);
                        when(myReservation.getUserId()).thenReturn(currentUserId);
                        when(myReservation.getId()).thenReturn(myReservationId);

                        Reservation otherReservation = mock(Reservation.class);
                        when(otherReservation.getUserId()).thenReturn("demo-user-2");
                        when(otherReservation.getId()).thenReturn(otherReservationId);

                        SeatReservationView mySeat = mock(SeatReservationView.class);
                        when(mySeat.getSeatNumber()).thenReturn((short) 1);
                        when(mySeat.getReservation()).thenReturn(myReservation);

                        SeatReservationView otherSeat = mock(SeatReservationView.class);
                        when(otherSeat.getSeatNumber()).thenReturn((short) 2);
                        when(otherSeat.getReservation()).thenReturn(otherReservation);

                        when(service.getSeatsWithReservation(date))
                                        .thenReturn(List.of(mySeat, otherSeat));

                        mockMvc.perform(get("/api/seats").param("date", date.toString())
                                        .header("X-Demo-User", currentUserId))
                                        .andExpect(status().isOk())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_JSON))
                                        .andExpect(header().string("Cache-Control", "no-store"))
                                        .andExpect(jsonPath("$.date").value(date.toString()))
                                        .andExpect(jsonPath("$.seats.length()").value(2))
                                        .andExpect(jsonPath("$.seats[0].seatNumber").value(1))
                                        .andExpect(jsonPath("$.seats[0].status").value("MINE"))
                                        .andExpect(jsonPath("$.seats[0].reservationId")
                                                        .value(myReservationId.toString()))
                                        .andExpect(jsonPath("$.seats[1].seatNumber").value(2))
                                        .andExpect(jsonPath("$.seats[1].status").value("OCCUPIED"))
                                        .andExpect(jsonPath("$.seats[1].reservationId")
                                                        .doesNotHaveJsonPath())
                                        .andExpect(jsonPath("$.seats[*].userId")
                                                        .doesNotHaveJsonPath());

                        verify(service).getSeatsWithReservation(date);
                }

                @ParameterizedTest(name = "{0}")
                @MethodSource("invalidGetSeatsCases")
                void 入力不正なら400(String description, String date, String userId) throws Exception {
                        String apiPath = "/api/seats";
                        var request = get(apiPath);

                        if (date != null) {
                                request.param("date", date);
                        }
                        if (userId != null) {
                                request.header("X-Demo-User", userId);
                        }

                        mockMvc.perform(request).andExpect(status().isBadRequest())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(header().string("Cache-Control", "no-store"))
                                        .andExpect(jsonPath("$.title").value("Bad Request"))
                                        .andExpect(jsonPath("$.status").value(400))
                                        .andExpect(jsonPath("$.detail").value("リクエストの内容が不正です。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verifyNoInteractions(service);
                }

                static Stream<Arguments> invalidGetSeatsCases() {
                        return Stream.of(Arguments.of("日付未指定", null, "demo-user-1"),
                                        Arguments.of("日付が空", "", "demo-user-1"),
                                        Arguments.of("日付の形式不正", "invalid-date", "demo-user-1"),
                                        Arguments.of("存在しない日付", "2026-02-30", "demo-user-1"),
                                        Arguments.of("ユーザーヘッダー未指定", "2026-10-10", null),
                                        Arguments.of("ユーザーヘッダー不正", "2026-10-10", "invalid-user"));
                }

                @Test
                void 想定外の例外なら500() throws Exception {
                        String apiPath = "/api/seats";
                        LocalDate date = LocalDate.now(FIXED_CLOCK);

                        when(service.getSeatsWithReservation(date))
                                        .thenThrow(new RuntimeException("内部システムエラー"));

                        mockMvc.perform(get(apiPath).param("date", date.toString())
                                        .header("X-Demo-User", "demo-user-1"))
                                        .andExpect(status().isInternalServerError())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(header().string("Cache-Control", "no-store"))
                                        .andExpect(jsonPath("$.title")
                                                        .value("Internal Server Error"))
                                        .andExpect(jsonPath("$.status").value(500))
                                        .andExpect(jsonPath("$.detail").value("システムエラーが発生しました。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verify(service).getSeatsWithReservation(date);
                }
        }

        @Nested
        class CreateReservationTests {
                @ParameterizedTest(name = "基準日から{0}日後の予約は201")
                @ValueSource(ints = {0, 1})
                void 正常な場合(int offsetDays) throws Exception {
                        UUID id = UUID.randomUUID();
                        String apiPath = "/api/reservations";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).plusDays(offsetDays);
                        short seatNumber = 1;
                        String userId = "demo-user-1";

                        Reservation saved = mock(Reservation.class);
                        when(saved.getId()).thenReturn(id);
                        when(saved.getReservationDate()).thenReturn(date);
                        when(saved.getSeatNumber()).thenReturn(seatNumber);

                        when(service.reserve(date, userId, seatNumber)).thenReturn(id);
                        when(service.getReservationById(id)).thenReturn(saved);

                        mockMvc.perform(post(apiPath).contentType(MediaType.APPLICATION_JSON)
                                        .header("X-Demo-User", userId).content("""
                                                        {"date": "%s", "seatNumber": %d}
                                                        """.formatted(date, seatNumber)))
                                        .andExpect(status().isCreated())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_JSON))
                                        .andExpect(jsonPath("$.id").value(id.toString()))
                                        .andExpect(jsonPath("$.date").value(date.toString()))
                                        .andExpect(jsonPath("$.seatNumber")
                                                        .value((int) seatNumber));

                        verify(service).reserve(date, userId, seatNumber);
                        verify(service).getReservationById(id);
                }

                @ParameterizedTest(name = "{0}")
                @MethodSource("invalidCreateReservationCases")
                void 入力不正なら400(String description, String body, String userId) throws Exception {
                        String apiPath = "/api/reservations";
                        var request = post(apiPath).contentType(MediaType.APPLICATION_JSON)
                                        .content(body);

                        if (userId != null) {
                                request.header("X-Demo-User", userId);
                        }

                        mockMvc.perform(request).andExpect(status().isBadRequest())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title").value("Bad Request"))
                                        .andExpect(jsonPath("$.status").value(400))
                                        .andExpect(jsonPath("$.detail").value("リクエストの内容が不正です。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verifyNoInteractions(service);
                }

                static Stream<Arguments> invalidCreateReservationCases() {
                        LocalDate futureDate = LocalDate.now(FIXED_CLOCK).plusDays(1);
                        LocalDate pastDate = LocalDate.now(FIXED_CLOCK).minusDays(1);
                        String validUser = "demo-user-1";

                        String validBody = """
                                        {"date": "%s", "seatNumber": 1}
                                        """.formatted(futureDate);

                        return Stream.of(Arguments.of("ユーザーヘッダー未指定", validBody, null),
                                        Arguments.of("ユーザーヘッダー不正", validBody, "invalid-user"),
                                        Arguments.of("本文未指定", "", validUser),
                                        Arguments.of("JSON構文不正", "{", validUser),
                                        Arguments.of("日付未指定", """
                                                        {"seatNumber": 1}
                                                        """, validUser), Arguments.of("日付がnull", """
                                                        {"date": null, "seatNumber": 1}
                                                        """, validUser), Arguments.of("日付の形式不正", """
                                                        {"date": "invalid-date", "seatNumber": 1}
                                                        """, validUser), Arguments.of("存在しない日付", """
                                                        {"date": "2026-10-32", "seatNumber": 1}
                                                        """, validUser), Arguments.of("過去日", """
                                                        {"date": "%s", "seatNumber": 1}
                                                        """.formatted(pastDate), validUser),
                                        Arguments.of("座席番号未指定", """
                                                        {"date": "%s"}
                                                        """.formatted(futureDate), validUser),
                                        Arguments.of("座席番号がnull", """
                                                        {"date": "%s", "seatNumber": null}
                                                        """.formatted(futureDate), validUser),
                                        Arguments.of("座席番号が0", """
                                                        {"date": "%s", "seatNumber": 0}
                                                        """.formatted(futureDate), validUser),
                                        Arguments.of("座席番号が負数", """
                                                        {"date": "%s", "seatNumber": -1}
                                                        """.formatted(futureDate), validUser),
                                        Arguments.of("座席番号が数値に変換できない文字列", """
                                                        {"date": "%s", "seatNumber": "abc"}
                                                        """.formatted(futureDate), validUser));
                }

                @Test
                void 座席マスタに存在しない番号なら400() throws Exception {
                        String apiPath = "/api/reservations";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).plusDays(1);
                        String userId = "demo-user-1";
                        short seatNumber = 31;

                        when(service.reserve(date, userId, seatNumber))
                                        .thenThrow(new InvalidSeatException());

                        mockMvc.perform(post(apiPath).contentType(MediaType.APPLICATION_JSON)
                                        .header("X-Demo-User", userId).content("""
                                                        {"date": "%s", "seatNumber": %d}
                                                        """.formatted(date, seatNumber)))
                                        .andExpect(status().isBadRequest())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title").value("Bad Request"))
                                        .andExpect(jsonPath("$.status").value(400))
                                        .andExpect(jsonPath("$.detail").value("リクエストの内容が不正です。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verify(service).reserve(date, userId, seatNumber);
                        verify(service, never()).getReservationById(any());
                }

                @ParameterizedTest(name = "予約済みなら409：userId={0}")
                @ValueSource(strings = {"demo-user-1", "demo-user-2"})
                void 予約済みなら409(String userId) throws Exception {
                        String apiPath = "/api/reservations";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).plusDays(1);
                        Short seatNumber = 1;

                        var cause = new ConstraintViolationException("重複予約",
                                        new SQLException("unique violation", "23505"),
                                        "uq_reservations_date_seat");

                        when(service.reserve(date, userId, seatNumber)).thenThrow(
                                        new DataIntegrityViolationException("予約の登録に失敗しました", cause));

                        mockMvc.perform(post(apiPath).contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                        {
                                                        "date": "%s",
                                                        "seatNumber": %d
                                                        }
                                                        """.formatted(date, seatNumber))
                                        .header("X-Demo-User", userId))
                                        .andExpect(status().isConflict())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title").value("Conflict"))
                                        .andExpect(jsonPath("$.status").value(409))
                                        .andExpect(jsonPath("$.detail").value("指定された日付・座席は予約済みです。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verify(service).reserve(date, userId, seatNumber);
                }

                @ParameterizedTest(name = "{0}")
                @MethodSource("reservationServerErrorCases")
                void 想定外の例外なら500(String description, RuntimeException exception) throws Exception {
                        String apiPath = "/api/reservations";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).plusDays(1);
                        short seatNumber = 1;
                        String userId = "demo-user-1";

                        when(service.reserve(date, userId, seatNumber)).thenThrow(exception);

                        mockMvc.perform(post(apiPath).contentType(MediaType.APPLICATION_JSON)
                                        .header("X-Demo-User", userId).content("""
                                                        {"date": "%s", "seatNumber": %d}
                                                        """.formatted(date, seatNumber)))
                                        .andExpect(status().isInternalServerError())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title")
                                                        .value("Internal Server Error"))
                                        .andExpect(jsonPath("$.status").value(500))
                                        .andExpect(jsonPath("$.detail").value("システムエラーが発生しました。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verify(service).reserve(date, userId, seatNumber);
                        verify(service, never()).getReservationById(any());
                }

                static Stream<Arguments> reservationServerErrorCases() {
                        return Stream.of(
                                        Arguments.of("主キー違反",
                                                        constraintViolation("pk_reservations",
                                                                        "23505")),
                                        Arguments.of("外部キー違反",
                                                        constraintViolation("fk_reservations_seat",
                                                                        "23503")),
                                        Arguments.of("NOT NULL違反",
                                                        constraintViolation(null, "23502")),
                                        Arguments.of("CHECK違反",
                                                        constraintViolation("test_check_constraint",
                                                                        "23514")),
                                        Arguments.of("その他のUNIQUE違反",
                                                        constraintViolation("test_other_unique",
                                                                        "23505")),
                                        Arguments.of("制約名不明のUNIQUE違反",
                                                        constraintViolation(null, "23505")),
                                        Arguments.of("原因例外なし",
                                                        new DataIntegrityViolationException(
                                                                        "内部DBエラー")),
                                        Arguments.of("一般例外", new RuntimeException("内部システムエラー")));
                }

                private static DataIntegrityViolationException constraintViolation(
                                String constraintName, String sqlState) {
                        var cause = new ConstraintViolationException("内部制約エラー",
                                        new SQLException("内部SQLエラー", sqlState), constraintName);

                        return new DataIntegrityViolationException("予約の登録に失敗しました", cause);
                }
        }

        @Nested
        class DeleteReservationTests {
                @ParameterizedTest(name = "基準日から{0}日後の予約はキャンセル可能")
                @ValueSource(ints = {0, 1})
                void 正常な場合(int offsetDays) throws Exception {
                        UUID id = UUID.randomUUID();
                        String apiPath = "/api/reservations/" + id;
                        String userId = "demo-user-1";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).plusDays(offsetDays);

                        Reservation reservation = mock(Reservation.class);
                        when(reservation.getId()).thenReturn(id);
                        when(reservation.getUserId()).thenReturn(userId);
                        when(reservation.getReservationDate()).thenReturn(date);
                        when(service.getReservationById(id)).thenReturn(reservation);

                        mockMvc.perform(delete(apiPath).header("X-Demo-User", userId))
                                        .andExpect(status().isNoContent())
                                        .andExpect(content().string(""));

                        verify(service).getReservationById(id);
                        verify(service).cancelReservation(id);
                }

                @ParameterizedTest(name = "{0}")
                @MethodSource("invalidDeleteReservationCases")
                void 入力不正なら400(String description, String reservationId, String userId)
                                throws Exception {
                        String apiPath = "/api/reservations/" + reservationId;
                        var request = delete(apiPath);

                        if (userId != null) {
                                request.header("X-Demo-User", userId);
                        }

                        mockMvc.perform(request).andExpect(status().isBadRequest())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title").value("Bad Request"))
                                        .andExpect(jsonPath("$.status").value(400))
                                        .andExpect(jsonPath("$.detail").value("リクエストの内容が不正です。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verifyNoInteractions(service);
                }

                static Stream<Arguments> invalidDeleteReservationCases() {
                        String validId = "550e8400-e29b-41d4-a716-446655440000";
                        String validUser = "demo-user-1";

                        return Stream.of(Arguments.of("予約IDがUUID形式ではない", "invalid-uuid", validUser),
                                        Arguments.of("予約IDに16進数以外の文字を含む",
                                                        "550e8400-e29b-41d4-a716-44665544000g",
                                                        validUser),
                                        Arguments.of("ユーザーヘッダー未指定", validId, null),
                                        Arguments.of("ユーザーヘッダーが空", validId, ""),
                                        Arguments.of("ユーザーヘッダー不正", validId, "invalid-user"));
                }

                @ParameterizedTest(name = "{0}日前の予約はキャンセル不可")
                @ValueSource(ints = {1, 30})
                void 過去日の予約なら400(int daysAgo) throws Exception {
                        UUID id = UUID.randomUUID();
                        String apiPath = "/api/reservations/" + id;
                        String userId = "demo-user-1";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).minusDays(daysAgo);

                        Reservation reservation = mock(Reservation.class);
                        when(reservation.getUserId()).thenReturn(userId);
                        when(reservation.getReservationDate()).thenReturn(date);
                        when(service.getReservationById(id)).thenReturn(reservation);

                        mockMvc.perform(delete(apiPath).header("X-Demo-User", userId))
                                        .andExpect(status().isBadRequest())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title").value("Bad Request"))
                                        .andExpect(jsonPath("$.status").value(400))
                                        .andExpect(jsonPath("$.detail").value("過去の予約はキャンセルできません。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verify(service).getReservationById(id);
                        verify(service, never()).cancelReservation(any());
                }

                @Test
                void 他人の予約なら403() throws Exception {
                        UUID id = UUID.randomUUID();
                        String apiPath = "/api/reservations/{id}";
                        LocalDate date = LocalDate.now(FIXED_CLOCK).plusDays(1);
                        Short seatNumber = 1;
                        String userId = "demo-user-1";
                        String otherUserId = "demo-user-2";

                        Reservation reservation = new Reservation(date, seatNumber, userId);
                        when(service.getReservationById(id)).thenReturn(reservation);

                        mockMvc.perform(delete(apiPath, id).header("X-Demo-User", otherUserId))
                                        .andExpect(status().isForbidden())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title").value("Forbidden"))
                                        .andExpect(jsonPath("$.status").value(403))
                                        .andExpect(jsonPath("$.detail")
                                                        .value("指定された予約はキャンセルできません。"))
                                        .andExpect(jsonPath("$.instance").value(
                                                        apiPath.replace("{id}", id.toString())));

                        verify(service, never()).cancelReservation(any());
                }

                @Test
                void 予約が存在しないなら404() throws Exception {
                        UUID id = UUID.randomUUID();
                        String apiPath = "/api/reservations/" + id;
                        String userId = "demo-user-1";

                        when(service.getReservationById(id)).thenReturn(null);

                        mockMvc.perform(delete(apiPath).header("X-Demo-User", userId))
                                        .andExpect(status().isNotFound())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title").value("Not Found"))
                                        .andExpect(jsonPath("$.status").value(404))
                                        .andExpect(jsonPath("$.detail").value("指定された予約が見つかりません。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verify(service).getReservationById(id);
                        verify(service, never()).cancelReservation(any());
                }

                @ParameterizedTest(name = "{0}")
                @ValueSource(strings = {"予約照会時", "予約削除時"})
                void 想定外の例外なら500(String failurePoint) throws Exception {
                        UUID id = UUID.randomUUID();
                        String apiPath = "/api/reservations/" + id;
                        String userId = "demo-user-1";
                        var exception = new RuntimeException("内部システムエラー");

                        boolean lookupFails = "予約照会時".equals(failurePoint);

                        if (lookupFails) {
                                when(service.getReservationById(id)).thenThrow(exception);
                        } else {
                                Reservation reservation = mock(Reservation.class);
                                when(reservation.getId()).thenReturn(id);
                                when(reservation.getUserId()).thenReturn(userId);
                                when(reservation.getReservationDate())
                                                .thenReturn(LocalDate.now(FIXED_CLOCK));
                                when(service.getReservationById(id)).thenReturn(reservation);

                                doThrow(exception).when(service).cancelReservation(id);
                        }

                        mockMvc.perform(delete(apiPath).header("X-Demo-User", userId))
                                        .andExpect(status().isInternalServerError())
                                        .andExpect(content().contentTypeCompatibleWith(
                                                        MediaType.APPLICATION_PROBLEM_JSON))
                                        .andExpect(jsonPath("$.title")
                                                        .value("Internal Server Error"))
                                        .andExpect(jsonPath("$.status").value(500))
                                        .andExpect(jsonPath("$.detail").value("システムエラーが発生しました。"))
                                        .andExpect(jsonPath("$.instance").value(apiPath));

                        verify(service).getReservationById(id);

                        if (lookupFails) {
                                verify(service, never()).cancelReservation(any());
                        } else {
                                verify(service).cancelReservation(id);
                        }
                }
        }
}
