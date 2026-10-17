package com.example.seat_reservation_api.controller;

import com.example.seat_reservation_api.config.SecurityConfig;
import com.example.seat_reservation_api.config.SecurityErrorConfig;
import com.example.seat_reservation_api.entity.Reservation;
import com.example.seat_reservation_api.exception.GlobalExceptionHandler;
import com.example.seat_reservation_api.exception.ReservationNotFoundException;
import com.example.seat_reservation_api.service.ReservationService;
import com.example.seat_reservation_api.support.JwtAuthenticatedTest;
import com.example.seat_reservation_api.support.TestJwtSupport;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminReservationController.class)
@ActiveProfiles("local")
@Import({GlobalExceptionHandler.class, SecurityConfig.class, SecurityErrorConfig.class,
        AdminReservationControllerTest.WebSecurityConfig.class})
class AdminReservationControllerTest extends JwtAuthenticatedTest {
    private static final String PATH = "/api/admin/reservations";
    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);
    private static final UUID ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ReservationService service;

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class WebSecurityConfig {
    }

    @Nested
    @DisplayName("管理者予約一覧取得")
    class GetReservationsTests {
        @ParameterizedTest(name = "Group={0}")
        @MethodSource("adminGroups")
        void 全所有者の予約情報を返す(List<String> groups) throws Exception {
            UUID otherId = UUID.randomUUID();
            List<Reservation> reservations = List.of(
                    reservation(ID, (short) 1, "user-a"),
                    reservation(otherId, (short) 2, "user-b"));
            when(service.getAllReservations(DATE)).thenReturn(reservations);

            String token = TestJwtSupport.token("admin-user",
                    claims -> claims.claim("cognito:groups", groups));
            mockMvc.perform(withToken(get(PATH).param("date", DATE.toString()), token))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().json("""
                            {"date":"2026-10-10","reservations":[
                              {"id":"%s","seatNumber":1,"userId":"user-a"},
                              {"id":"%s","seatNumber":2,"userId":"user-b"}]}
                            """.formatted(ID, otherId)));

            verify(service).getAllReservations(DATE);
            verifyNoMoreInteractions(service);
        }

        static Stream<List<String>> adminGroups() {
            return Stream.of(List.of("ADMINS"), List.of("USERS", "ADMINS"));
        }

        @Test
        void 予約がなければ空配列を返す() throws Exception {
            when(service.getAllReservations(DATE)).thenReturn(List.of());

            mockMvc.perform(asAdmin(get(PATH).param("date", DATE.toString())))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.date").value(DATE.toString()))
                    .andExpect(jsonPath("$.reservations").isArray())
                    .andExpect(jsonPath("$.reservations.length()").value(0));

            verify(service).getAllReservations(DATE);
            verifyNoMoreInteractions(service);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"invalid-date", "2026-02-30"})
        void 入力不正なら400(String date) throws Exception {
            var request = get(PATH);
            if (date != null) {
                request.param("date", date);
            }
            mockMvc.perform(asAdmin(request)).andExpectAll(problem(HttpStatus.BAD_REQUEST,
                    "リクエストの内容が不正です。", PATH));
            verifyNoInteractions(service);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-token")
        void 認証不正なら401(String token) throws Exception {
            mockMvc.perform(withToken(get(PATH).param("date", DATE.toString()), token))
                    .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                    .andExpectAll(problem(HttpStatus.UNAUTHORIZED, "認証が必要です。", PATH));
            verifyNoInteractions(service);
        }

        @Test
        void 一般ユーザーなら403() throws Exception {
            mockMvc.perform(withToken(get(PATH).param("date", DATE.toString()),
                            TestJwtSupport.userToken("user-a")))
                    .andExpectAll(problem(HttpStatus.FORBIDDEN, "操作権限がありません。", PATH));
            verifyNoInteractions(service);
        }

        @Test
        void 認可エラーなら403() throws Exception {
            when(service.getAllReservations(DATE)).thenThrow(new AccessDeniedException("denied"));
            mockMvc.perform(asAdmin(get(PATH).param("date", DATE.toString())))
                    .andExpectAll(problem(HttpStatus.FORBIDDEN, "操作権限がありません。", PATH));
            verify(service).getAllReservations(DATE);
            verifyNoMoreInteractions(service);
        }

        @Test
        void 想定外の例外なら500() throws Exception {
            when(service.getAllReservations(DATE)).thenThrow(new RuntimeException("内部システムエラー"));
            mockMvc.perform(asAdmin(get(PATH).param("date", DATE.toString())))
                    .andExpectAll(problem(HttpStatus.INTERNAL_SERVER_ERROR,
                            "システムエラーが発生しました。", PATH));
            verify(service).getAllReservations(DATE);
            verifyNoMoreInteractions(service);
        }
    }

    @Nested
    @DisplayName("管理者予約キャンセル")
    class DeleteReservationTests {
        @Test
        void 正常な場合() throws Exception {
            mockMvc.perform(asAdmin(delete(reservationPath(ID.toString()))))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
            verify(service).cancelReservationAsAdmin(ID);
            verifyNoMoreInteractions(service);
        }

        @ParameterizedTest
        @ValueSource(strings = {"invalid-uuid", "550e8400-e29b-41d4-a716-44665544000g"})
        void 入力不正なら400(String id) throws Exception {
            String path = reservationPath(id);
            mockMvc.perform(asAdmin(delete(path))).andExpectAll(problem(HttpStatus.BAD_REQUEST,
                    "リクエストの内容が不正です。", path));
            verifyNoInteractions(service);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "invalid-token")
        void 認証不正なら401(String token) throws Exception {
            String path = reservationPath(ID.toString());
            mockMvc.perform(withToken(delete(path), token))
                    .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                    .andExpectAll(problem(HttpStatus.UNAUTHORIZED, "認証が必要です。", path));
            verifyNoInteractions(service);
        }

        @Test
        void 一般ユーザーなら403() throws Exception {
            String path = reservationPath(ID.toString());
            mockMvc.perform(withToken(delete(path), TestJwtSupport.userToken("user-a")))
                    .andExpectAll(problem(HttpStatus.FORBIDDEN, "操作権限がありません。", path));
            verifyNoInteractions(service);
        }

        @Test
        void 過去日エラーなら400() throws Exception {
            doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "過去の予約はキャンセルできません。"))
                    .when(service).cancelReservationAsAdmin(ID);
            assertCancellationProblem(HttpStatus.BAD_REQUEST, "過去の予約はキャンセルできません。");
        }

        @Test
        void 予約なしエラーなら404() throws Exception {
            doThrow(new ReservationNotFoundException()).when(service).cancelReservationAsAdmin(ID);
            assertCancellationProblem(HttpStatus.NOT_FOUND, "指定された予約が見つかりません。");
        }

        @Test
        void 認可エラーなら403() throws Exception {
            doThrow(new AccessDeniedException("denied")).when(service).cancelReservationAsAdmin(ID);
            assertCancellationProblem(HttpStatus.FORBIDDEN, "操作権限がありません。");
        }

        @Test
        void 想定外の例外なら500() throws Exception {
            doThrow(new RuntimeException("内部システムエラー")).when(service).cancelReservationAsAdmin(ID);
            assertCancellationProblem(HttpStatus.INTERNAL_SERVER_ERROR, "システムエラーが発生しました。");
        }

        private void assertCancellationProblem(HttpStatus status, String detail) throws Exception {
            String path = reservationPath(ID.toString());
            mockMvc.perform(asAdmin(delete(path))).andExpectAll(problem(status, detail, path));
            verify(service).cancelReservationAsAdmin(ID);
            verifyNoMoreInteractions(service);
        }
    }

    private static Reservation reservation(UUID id, short seatNumber, String userId) {
        Reservation reservation = mock(Reservation.class);
        when(reservation.getId()).thenReturn(id);
        when(reservation.getSeatNumber()).thenReturn(seatNumber);
        when(reservation.getUserId()).thenReturn(userId);
        return reservation;
    }

    private static String reservationPath(String id) {
        return PATH + "/" + id;
    }

    private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return withToken(request, TestJwtSupport.adminToken("admin-user"));
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request,
            String token) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request;
    }

    private static ResultMatcher[] problem(HttpStatus status, String detail, String path) {
        return new ResultMatcher[] {status().is(status.value()),
                content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                jsonPath("$.title").value(status.getReasonPhrase()),
                jsonPath("$.status").value(status.value()),
                jsonPath("$.detail").value(detail),
                jsonPath("$.instance").value(path)};
    }
}
