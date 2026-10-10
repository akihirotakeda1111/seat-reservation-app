package com.example.seat_reservation_api.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SeatsRequestTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 10, 10);

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

    @ParameterizedTest(name = "基準日から{0}日の照会が可能")
    @ValueSource(ints = {-1, 0, 1})
    void 検証結果が正常(int offsetDays) {
        SeatsRequest request = new SeatsRequest(BASE_DATE.plusDays(offsetDays));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void 日付がnullなら必須エラー() {
        SeatsRequest request = new SeatsRequest(null);

        Set<ConstraintViolation<SeatsRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString() + ":" + v.getConstraintDescriptor()
                        .getAnnotation().annotationType().getSimpleName())
                .containsExactly("date:NotNull");
    }
}
