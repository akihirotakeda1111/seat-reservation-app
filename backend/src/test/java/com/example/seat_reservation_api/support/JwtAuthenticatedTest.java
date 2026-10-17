package com.example.seat_reservation_api.support;

import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestJwtSupport.ISSUER,
        "app.security.cognito-client-id=" + TestJwtSupport.CLIENT_ID
})
public abstract class JwtAuthenticatedTest {
    @TestBean(name = "jwtDecoder", methodName = "testJwtDecoder", enforceOverride = true)
    protected JwtDecoder jwtDecoder;

    static JwtDecoder testJwtDecoder() {
        return TestJwtSupport.decoder();
    }
}
