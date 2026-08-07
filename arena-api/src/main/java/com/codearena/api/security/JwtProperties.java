package com.codearena.api.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT configuration, bound from {@code arena.jwt.*}.
 *
 * <p>Validated at startup rather than at first use: a missing or too-short signing key should
 * stop the application from booting, not surface as a 500 the first time somebody logs in.
 *
 * @param secret     HMAC signing key. Must be at least 32 bytes to match the 256-bit output of
 *                   HS256 - a shorter key silently weakens the signature.
 * @param issuer     the {@code iss} claim, checked on every incoming token
 * @param accessTtl  how long an access token stays valid. Short, because a JWT cannot be
 *                   revoked once signed.
 * @param refreshTtl how long a refresh token stays valid. Long, but revocable, because refresh
 *                   tokens are opaque database rows rather than JWTs.
 */
@Validated
@ConfigurationProperties(prefix = "arena.jwt")
public record JwtProperties(

        @NotBlank
        @Size(min = 32, message = "the JWT signing key must be at least 32 bytes for HS256")
        String secret,

        @NotBlank
        String issuer,

        Duration accessTtl,

        Duration refreshTtl
) {

    public JwtProperties {
        accessTtl = accessTtl == null ? Duration.ofMinutes(15) : accessTtl;
        refreshTtl = refreshTtl == null ? Duration.ofDays(7) : refreshTtl;
    }
}
