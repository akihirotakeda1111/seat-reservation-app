package com.example.seat_reservation_api.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
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
        factory = Validation.buildDefaultValidatorFactory();

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
        return Stream.of(Arguments.of("当日はDTO検証を通過", (short) 1, TODAY),
                Arguments.of("翌日はDTO検証を通過", (short) 1, TODAY.plusDays(1)),
                Arguments.of("未来日はDTO検証を通過", (short) 30, TODAY.plusYears(1)),
                Arguments.of("過去日判定はServiceで行う", (short) 1, TODAY.minusDays(1)));
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
                Arguments.of("座席番号が0", (short) 0, TODAY, Set.of("seatNumber:Min")),
                Arguments.of("座席番号が負数", (short) -1, TODAY, Set.of("seatNumber:Min")),
                Arguments.of("両項目がnull", null, null, Set.of("seatNumber:NotNull", "date:NotNull")));
    }
}
