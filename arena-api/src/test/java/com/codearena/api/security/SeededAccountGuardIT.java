package com.codearena.api.security;

import com.codearena.api.domain.User;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.support.MongoTestContainer;
import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard that stops a public deployment shipping the README's admin password.
 *
 * <p>Runs against the real seeded database, because the thing being asserted is a fact about
 * migration V3's data: that {@code admin} starts life with a password anyone can read in the
 * repository. A test with hand-built fixtures would assert the guard's arithmetic and miss the
 * point entirely.
 */
@SpringBootTest
@DisplayName("SeededAccountGuard")
class SeededAccountGuardIT {

    private static final String PUBLISHED_ADMIN_PASSWORD = "Admin123!";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SeededAccountGuard guard;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        RedisTestContainer.registerProperties(registry);
        MongoTestContainer.registerProperties(registry);
        // No broker: this test never publishes or consumes, and the producer connects lazily.
        // Leaving the listeners off keeps the context free of a Kafka container it does not need -
        // which on a memory-constrained machine is the difference between passing and timing out.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
    }

    @AfterEach
    void restoreTheSeededPassword() {
        // Other tests sign in as admin, and this one rotates the password. Put it back rather
        // than leaving the suite's fixtures depending on execution order.
        userRepository.findByUsername("admin").ifPresent(admin -> {
            admin.setPasswordHash(passwordEncoder.encode(PUBLISHED_ADMIN_PASSWORD));
            userRepository.save(admin);
        });
    }

    @Test
    @DisplayName("the seeded admin really does use the password printed in the README")
    void theProblemIsReal() {
        // If this ever fails, the guard has become unnecessary - which is worth knowing.
        String hash = userRepository.findByUsername("admin").orElseThrow().getPasswordHash();

        assertThat(passwordEncoder.matches(PUBLISHED_ADMIN_PASSWORD, hash))
                .as("migration V3 seeds a publicly known admin password")
                .isTrue();
    }

    @Test
    @DisplayName("PERMIT leaves the account alone, so local development is unaffected")
    void permitChangesNothing() {
        new SeededAccountGuard(userRepository, passwordEncoder, permit()).enforce();

        String hash = userRepository.findByUsername("admin").orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches(PUBLISHED_ADMIN_PASSWORD, hash)).isTrue();
    }

    @Test
    @DisplayName("LOCKED without a password refuses to start rather than warning")
    void lockedWithoutAPasswordFails() {
        // The failure being prevented is silent, so the check must not be. A warning here would
        // scroll past in a deploy log and the site would be open.
        SeededAccountGuard locked =
                new SeededAccountGuard(userRepository, passwordEncoder, locked(null));

        assertThatThrownBy(locked::enforce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARENA_ADMIN_PASSWORD");
    }

    @Test
    @DisplayName("LOCKED rejects the published password as a replacement")
    void lockedRejectsTheKnownPassword() {
        SeededAccountGuard locked = new SeededAccountGuard(
                userRepository, passwordEncoder, locked(PUBLISHED_ADMIN_PASSWORD));

        assertThatThrownBy(locked::enforce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("published in the README");
    }

    @Test
    @DisplayName("LOCKED with a real password rotates the admin account")
    void lockedRotatesThePassword() {
        new SeededAccountGuard(userRepository, passwordEncoder, locked("a-genuinely-new-secret"))
                .enforce();

        String hash = userRepository.findByUsername("admin").orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches("a-genuinely-new-secret", hash)).isTrue();
        assertThat(passwordEncoder.matches(PUBLISHED_ADMIN_PASSWORD, hash))
                .as("the published password must no longer work")
                .isFalse();
    }

    @Test
    @DisplayName("rotating twice does not rewrite an already-rotated hash")
    void rotationIsIdempotent() {
        SeededAccountGuard locked =
                new SeededAccountGuard(userRepository, passwordEncoder, locked("stable-secret"));

        locked.enforce();
        String first = userRepository.findByUsername("admin").orElseThrow().getPasswordHash();
        locked.enforce();
        String second = userRepository.findByUsername("admin").orElseThrow().getPasswordHash();

        // bcrypt salts every encode, so an unconditional re-encode would produce a different hash
        // on every restart - harmless, but it makes "did anything change?" unanswerable from the
        // data alone.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("demo users keep their sign-in by default, so a public demo is not empty")
    void demoUsersSurviveByDefault() {
        new SeededAccountGuard(userRepository, passwordEncoder, locked("some-secret")).enforce();

        String aliceHash = userRepository.findByUsername("alice").orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches("Password123!", aliceHash)).isTrue();
    }

    private static SecurityHardeningProperties permit() {
        return new SecurityHardeningProperties(
                SecurityHardeningProperties.SeededAccounts.PERMIT, null, false);
    }

    private static SecurityHardeningProperties locked(String adminPassword) {
        return new SecurityHardeningProperties(
                SecurityHardeningProperties.SeededAccounts.LOCKED, adminPassword, false);
    }

    /** Guards against the seeded row disappearing and the tests above quietly passing on nothing. */
    @Test
    @DisplayName("the seeded admin exists at all")
    void adminExists() {
        assertThat(userRepository.findByUsername("admin")).map(User::getUsername).hasValue("admin");
    }
}
