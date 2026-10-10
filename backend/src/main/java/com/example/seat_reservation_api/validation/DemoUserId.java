package com.example.seat_reservation_api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "demo-user-[12]")
@ReportAsSingleViolation
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.RECORD_COMPONENT,
        ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DemoUserId {

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
