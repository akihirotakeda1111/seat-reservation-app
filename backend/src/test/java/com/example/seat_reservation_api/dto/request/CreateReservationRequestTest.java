package com.example.seat_reservation_api.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreateReservationRequestTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ZoneId zone = ZoneId.of("Asia/Tokyo");
        Clock clock = Clock.fixed(TODAY.atStartOfDay(zone).toInstant(), zone);

        factory = Validation.byDefaultProvider().configure().clockProvider(() -> clock)
                .buildValidatorFactory();

        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validCases")
    void 検証結果が正常(String description, Short seatNumber, LocalDate date) {
        CreateReservationRequest request = new CreateReservationRequest(seatNumber, date);

        assertThat(validator.validate(request)).isEmpty();
    }

    static Stream<Arguments> validCases() {
        return Stream.of(Arguments.of("当日は予約可能", (short) 1, TODAY),
                Arguments.of("翌日は予約可能", (short) 1, TODAY.plusDays(1)),
                Arguments.of("未来日は予約可能", (short) 30, TODAY.plusYears(1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void 不正な入力は検証エラー(String description, Short seatNumber, LocalDate date,
            Set<String> expectedViolations) {
        CreateReservationRequest request = new CreateReservationRequest(seatNumber, date);

        Set<ConstraintViolation<CreateReservationRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString() + ":"
                        + v.getConstraintDescriptor().getAnnotation().annotationType()
                                .getSimpleName())
                .containsExactlyInAnyOrderElementsOf(expectedViolations);
    }

    static Stream<Arguments> invalidCases() {
        return Stream.of(Arguments.of("座席番号がnull", null, TODAY, Set.of("seatNumber:NotNull")),
                Arguments.of("日付がnull", (short) 1, null, Set.of("date:NotNull")),
                Arguments.of("前日は予約不可", (short) 1, TODAY.minusDays(1),
                        Set.of("date:FutureOrPresent")),
                Arguments.of("両項目がnull", null, null, Set.of("seatNumber:NotNull", "date:NotNull")),
                Arguments.of("座席番号がnullかつ過去日", null, TODAY.minusDays(1),
                        Set.of("seatNumber:NotNull", "date:FutureOrPresent")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dateRolloverCases")
    void 日本時間で日付が切り替わる(String description, Instant currentInstant, LocalDate reservationDate,
            Set<String> expectedViolations) {
        Clock clock = Clock.fixed(currentInstant, ZoneId.of("Asia/Tokyo"));

        Validator caseValidator = factory.usingContext().clockProvider(() -> clock).getValidator();

        CreateReservationRequest request = new CreateReservationRequest((short) 1, reservationDate);

        Set<ConstraintViolation<CreateReservationRequest>> violations =
                caseValidator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString() + ":"
                        + v.getConstraintDescriptor().getAnnotation().annotationType()
                                .getSimpleName())
                .containsExactlyInAnyOrderElementsOf(expectedViolations);
    }

    static Stream<Arguments> dateRolloverCases() {
        Instant beforeMidnight = Instant.parse("2026-10-09T14:59:59Z");
        Instant atMidnight = Instant.parse("2026-10-09T15:00:00Z");

        LocalDate october9 = LocalDate.of(2026, 10, 9);
        LocalDate october10 = LocalDate.of(2026, 10, 10);

        Set<String> valid = Set.of();
        Set<String> pastDateError = Set.of("date:FutureOrPresent");

        return Stream.of(Arguments.of("直前：10月9日は当日なので予約可能", beforeMidnight, october9, valid),
                Arguments.of("直後：10月9日は過去日なので予約不可", atMidnight, october9, pastDateError),
                Arguments.of("直前：10月10日は翌日なので予約可能", beforeMidnight, october10, valid),
                Arguments.of("直後：10月10日は当日なので予約可能", atMidnight, october10, valid));
    }
}
