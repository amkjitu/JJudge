package com.codearena.api.security;

import com.codearena.api.domain.User;
import com.codearena.common.domain.Role;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips real signed tokens through a real decoder - no mocking of the crypto, because
 * the interesting failures (wrong key, wrong issuer, expired) only exist at that level.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    private static final String SECRET = "test-only-signing-key-0123456789abcdefghijklmnop";
    private static final String ISSUER = "codearena-test";
    /**
     * Anchored to the real clock rather than a hard-coded instant: the decoder validates
     * expiry against wall-clock time, so a fixed literal date silently becomes "already
     * expired" the moment it drifts into the past.
     */
    private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

    private final JwtProperties properties =
            new JwtProperties(SECRET, ISSUER, Duration.ofMinutes(15), Duration.ofDays(7));

    private final Clock fixedClock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static SecretKeySpec key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private JwtService service() {
        return new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(key(SECRET))),
                properties, fixedClock);
    }

    private JwtDecoder decoder(String secret, String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(secret))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    private static User user() {
        return User.builder().id(3L).username("bob").role(Role.USER).build();
    }

    @Test
    @DisplayName("issues a token carrying subject, user id and roles")
    void issuesExpectedClaims() {
        JwtService.IssuedAccessToken issued = service().issueAccessToken(user());

        Jwt decoded = decoder(SECRET, ISSUER).decode(issued.value());

        assertThat(decoded.getSubject()).isEqualTo("bob");
        // Cast to Object: Jwt#getClaim is generic, and AssertJ cannot pick an overload for T.
        assertThat((Object) decoded.getClaim(JwtService.USER_ID_CLAIM)).isEqualTo(3L);
        assertThat(decoded.getClaimAsStringList(JwtService.ROLES_CLAIM)).isEqualTo(List.of("USER"));
        // getIssuer() coerces to URL, which a bare service name is not - read the raw claim.
        assertThat(decoded.getClaimAsString("iss")).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("expiry follows the configured TTL")
    void expiryFollowsTtl() {
        JwtService.IssuedAccessToken issued = service().issueAccessToken(user());

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(decoder(SECRET, ISSUER).decode(issued.value()).getExpiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("an admin token carries the ADMIN role")
    void adminRole() {
        User admin = User.builder().id(1L).username("admin").role(Role.ADMIN).build();

        Jwt decoded = decoder(SECRET, ISSUER).decode(service().issueAccessToken(admin).value());

        assertThat(decoded.getClaimAsStringList(JwtService.ROLES_CLAIM)).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("a token signed with a different key is rejected")
    void wrongKeyIsRejected() {
        String token = service().issueAccessToken(user()).value();

        assertThatThrownBy(() ->
                decoder("a-completely-different-key-0123456789abcdefgh", ISSUER).decode(token))
                .hasMessageContaining("signature");
    }

    @Test
    @DisplayName("a token from another issuer is rejected even when the key matches")
    void wrongIssuerIsRejected() {
        // The scenario this guards: two services sharing a signing secret, where one should
        // not accept the other's tokens.
        String token = service().issueAccessToken(user()).value();

        assertThatThrownBy(() -> decoder(SECRET, "some-other-service").decode(token))
                .hasMessageContaining("iss");
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expiredTokenIsRejected() {
        JwtService pastService = new JwtService(
                new NimbusJwtEncoder(new ImmutableSecret<>(key(SECRET))),
                properties,
                Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneOffset.UTC));

        String token = pastService.issueAccessToken(user()).value();

        assertThatThrownBy(() -> decoder(SECRET, ISSUER).decode(token))
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("properties fall back to sane TTLs when unset")
    void ttlDefaults() {
        JwtProperties defaults = new JwtProperties(SECRET, ISSUER, null, null);

        assertThat(defaults.accessTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(defaults.refreshTtl()).isEqualTo(Duration.ofDays(7));
    }
}
