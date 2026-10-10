package com.example.seat_reservation_api.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@Configuration(proxyBeanMethods = false)
public class ValidationConfig {

    @Bean
    LocalValidatorFactoryBean validator(Clock clock) {
        var validator = new LocalValidatorFactoryBean();
        validator.setConfigurationInitializer(
                configuration -> configuration.clockProvider(() -> clock));
        return validator;
    }
}
