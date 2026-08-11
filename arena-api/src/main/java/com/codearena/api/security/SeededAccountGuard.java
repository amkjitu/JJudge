package com.codearena.api.security;

import com.codearena.api.domain.User;
import com.codearena.api.repository.UserRepository;
import com.codearena.common.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Stops a public deployment shipping the seeded demo passwords.
 *
 * <h2>The problem this exists for</h2>
 *
 * <p>Migration {@code V3} seeds four accounts with known bcrypt hashes, and the README prints
 * their passwords - deliberately, because a demo nobody can log into demonstrates nothing. One of
 * them is {@code admin}, which holds {@link Role#ADMIN}: problem CRUD and the actuator endpoints.
 *
 * <p>On a laptop that is a feature. On a public URL it is an unauthenticated stranger with write
 * access to the catalogue, and the credentials are in the repository they are reading. Nothing
 * about the application would look wrong; it would simply be open.
 *
 * <h2>Why it refuses to start rather than warning</h2>
 *
 * <p>A warning in a log nobody reads is the same as no warning. The failure mode being prevented
 * here is silent, so the check is not: with {@code arena.security.seeded-accounts=locked} and no
 * admin password supplied, the context fails and the deployment stops. An operator who sees the
 * error fixes it in a minute; an operator who sees a warning has already moved on.
 *
 * <p>{@code permit} is the default, because the overwhelmingly common case is somebody running
 * this locally to look at it, and making them set a secret first would be friction with no
 * security benefit on a laptop. Production opts in via the compose overlay.
 */
@Component
public class SeededAccountGuard {

    private static final Logger log = LoggerFactory.getLogger(SeededAccountGuard.class);

    /** The usernames migration V3 creates. */
    private static final List<String> SEEDED_USERNAMES = List.of("admin", "alice", "bob", "carol");

    /** Their published passwords, so the guard can tell a rotated account from an untouched one. */
    private static final List<String> PUBLISHED_PASSWORDS = List.of("Admin123!", "Password123!");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityHardeningProperties properties;

    public SeededAccountGuard(UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              SecurityHardeningProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * Runs after startup so a database that is briefly unavailable delays the check rather than
     * killing the process - but the outcome is still fatal, because continuing to serve with a
     * published admin password is worse than not serving.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void enforce() {
        if (properties.seededAccounts() == SecurityHardeningProperties.SeededAccounts.PERMIT) {
            if (adminStillUsesThePublishedPassword()) {
                log.warn("The seeded 'admin' account still uses the password printed in the "
                        + "README. Fine locally; set ARENA_SECURITY_SEEDED_ACCOUNTS=locked and "
                        + "ARENA_ADMIN_PASSWORD before exposing this to a network.");
            }
            return;
        }

        String newPassword = properties.adminPassword();
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalStateException("""
                    arena.security.seeded-accounts=locked but no admin password was supplied.

                    The seeded 'admin' account has ADMIN rights and its password is published in
                    the README, so starting like this would expose problem CRUD to anyone who has
                    read the repository. Set ARENA_ADMIN_PASSWORD to a strong value and restart.
                    """);
        }

        if (PUBLISHED_PASSWORDS.contains(newPassword)) {
            throw new IllegalStateException(
                    "ARENA_ADMIN_PASSWORD is one of the passwords published in the README. "
                            + "Choose a different one.");
        }

        rotateAdminPassword(newPassword);
        demoteOtherSeededAccountsIfRequested();
    }

    private void rotateAdminPassword(String newPassword) {
        userRepository.findByUsername("admin").ifPresentOrElse(admin -> {
            if (passwordEncoder.matches(newPassword, admin.getPasswordHash())) {
                // Already rotated on a previous start. Re-encoding would work but writes a new
                // hash on every restart for no reason.
                return;
            }
            admin.setPasswordHash(passwordEncoder.encode(newPassword));
            userRepository.save(admin);
            log.info("Rotated the seeded 'admin' password from the value published in the README.");
        }, () -> log.info("No seeded 'admin' account present; nothing to rotate."));
    }

    /**
     * The demo users are left alone by default. They are ordinary accounts - they can submit and
     * appear on the leaderboard, which is the whole point of a public demo - and locking them out
     * would leave visitors with nothing to click. Set
     * {@code arena.security.lock-demo-users=true} to close that too.
     */
    private void demoteOtherSeededAccountsIfRequested() {
        if (!properties.lockDemoUsers()) {
            log.info("Demo users (alice, bob, carol) remain available for sign-in.");
            return;
        }

        SEEDED_USERNAMES.stream()
                .filter(username -> !username.equals("admin"))
                .forEach(username -> userRepository.findByUsername(username).ifPresent(user -> {
                    // A null hash means no password can ever match: the row survives, so its
                    // submissions and rating still make the leaderboard look real, but nobody
                    // can sign in as it.
                    user.setPasswordHash(null);
                    userRepository.save(user);
                }));
        log.info("Demo users can no longer sign in; their history is kept so the site is not empty.");
    }

    private boolean adminStillUsesThePublishedPassword() {
        return userRepository.findByUsername("admin")
                .map(User::getPasswordHash)
                .filter(hash -> hash != null && passwordEncoder.matches("Admin123!", hash))
                .isPresent();
    }
}
