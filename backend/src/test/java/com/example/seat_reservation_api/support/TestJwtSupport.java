package com.example.seat_reservation_api.support;

import com.example.seat_reservation_api.config.CognitoJwtValidators;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public final class TestJwtSupport {
    public static final String ISSUER = "https://issuer.example.test";
    public static final String CLIENT_ID = "test-client";
    private static final String KEY_ID = "test-key";
    private static final KeyPair KEY_PAIR = generateKeyPair();
    private static final JwtEncoder ENCODER = createEncoder();

    private TestJwtSupport() {
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Failed to generate test RSA key", exception);
        }
    }

    private static JwtEncoder createEncoder() {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) KEY_PAIR.getPublic())
                .privateKey((RSAPrivateKey) KEY_PAIR.getPrivate()).keyID(KEY_ID).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
    }

    public static JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) KEY_PAIR.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(CognitoJwtValidators.create(ISSUER, CLIENT_ID));
        return decoder;
    }

    public static String userToken(String sub) {
        return token(sub, claims -> {
        });
    }

    public static String adminToken(String sub) {
        return token(sub, claims -> claims.claim("cognito:groups", List.of("ADMINS")));
    }

    public static String token(String sub, Consumer<JwtClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer(ISSUER).subject(sub)
                .issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("token_use", "access").claim("client_id", CLIENT_ID);
        customize.accept(claims);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(KEY_ID).type("JWT").build();
        return ENCODER.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
