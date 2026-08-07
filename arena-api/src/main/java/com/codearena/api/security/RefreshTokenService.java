package com.codearena.api.security;

import com.codearena.api.domain.RefreshToken;
import com.codearena.api.domain.User;
import com.codearena.api.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>The token handed to the client is 256 bits of {@link SecureRandom} output, base64url
 * encoded. Only its SHA-256 digest is stored, so a database dump does not yield usable
 * sessions.
 *
 * <p>Every redemption rotates: the presented token is revoked and a fresh one issued, linked
 * back through {@code replaced_by}. If an already-rotated token is presented again, the only
 * consistent explanation is that it leaked - so every live token for that user is revoked,
 * forcing a real login. This costs a legitimate user one re-login in the rare case of a
 * genuinely concurrent refresh, which is the right side of that trade.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties properties;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               JwtProperties properties,
                               Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public IssuedRefreshToken issue(User user) {
        String rawToken = generateToken();
        Instant now = clock.instant();

        RefreshToken stored = refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .issuedAt(now)
                .expiresAt(now.plus(properties.refreshTtl()))
                .build());

        return new IssuedRefreshToken(rawToken, stored, stored.getExpiresAt());
    }

    /**
     * Redeems a refresh token and returns its replacement.
     *
     * @throws InvalidRefreshTokenException if the token is unknown, expired, or already used
     */
    @Transactional
    public IssuedRefreshToken rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken presented = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token is not recognised"));

        if (presented.isRevoked()) {
            // Reuse of a rotated token: assume compromise and drop every session for the user.
            //
            // This has to run in its own transaction. Revoking in the current one and then
            // throwing would roll the revocation straight back - the exception that reports the
            // compromise would undo the response to it, leaving the attacker's newer token
            // live. The bug is invisible without a test that checks the *other* token
            // afterwards, which is why AuthApiIT does exactly that.
            Long userId = presented.getUser().getId();
            Integer revoked = requiresNewTransaction.execute(status ->
                    refreshTokenRepository.revokeAllForUser(userId, now));

            log.warn("Refresh token reuse detected for user id {}; revoked {} live token(s)",
                    userId, revoked);
            throw new InvalidRefreshTokenException(
                    "Refresh token has already been used; all sessions have been revoked");
        }

        if (presented.isExpired(now)) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        IssuedRefreshToken replacement = issue(presented.getUser());
        presented.revoke(now);
        presented.setReplacedBy(replacement.stored());

        return replacement;
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .ifPresent(token -> token.revoke(clock.instant()));
    }

    @Transactional
    public int revokeAllForUser(Long userId) {
        return refreshTokenRepository.revokeAllForUser(userId, clock.instant());
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, not BCrypt. BCrypt's work factor exists to slow brute-forcing of low-entropy
     * human passwords; a 256-bit random token has nothing to brute-force, and this digest runs
     * on every single refresh.
     */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK specification", e);
        }
    }

    public record IssuedRefreshToken(String value, RefreshToken stored, Instant expiresAt) {
    }
}
