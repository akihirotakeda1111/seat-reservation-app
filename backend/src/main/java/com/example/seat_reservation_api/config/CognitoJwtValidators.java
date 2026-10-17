package com.example.seat_reservation_api.config;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** 本番とテストの共用Claim検証 */
public final class CognitoJwtValidators {
        private CognitoJwtValidators() {
        }

        public static OAuth2TokenValidator<Jwt> create(String issuer, String clientId) {
                OAuth2TokenValidator<Jwt> claimsValidator = jwt -> {
                        Object sub = jwt.getClaims().get("sub");
                        boolean valid = "access".equals(jwt.getClaims().get("token_use"))
                                        && clientId.equals(jwt.getClaims().get("client_id"))
                                        && sub instanceof String subject
                                        && !subject.isBlank()
                                        && subject.length() <= 128
                                        && jwt.getExpiresAt() != null;
                        return valid ? OAuth2TokenValidatorResult.success()
                                        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                                                        "Required access token claims are invalid.", null));
                };
                return new DelegatingOAuth2TokenValidator<>(
                                JwtValidators.createDefaultWithIssuer(issuer), claimsValidator);
        }
}
