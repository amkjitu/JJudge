package com.codearena.api.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Time is injected, so these run instantly instead of sleeping through a real window.
 */
@DisplayName("InMemoryRateLimiter")
class InMemoryRateLimiterTest {

    /** A clock the test can wind forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-08-07T12:00:00Z");

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();

    private InMemoryRateLimiter limiter(int permits, Duration window) {
        return new InMemoryRateLimiter(
                new RateLimitProperties(permits, window, true), clock);
    }

    @Test
    @DisplayName("allows exactly the configured number of requests, then refuses")
    void allowsUpToCapacity() {
        InMemoryRateLimiter limiter = limiter(3, Duration.ofMinutes(1));

        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        assertThat(limiter.tryConsume("user").allowed()).isFalse();
    }

    @Test
    @DisplayName("reports how many permits are left")
    void reportsRemaining() {
        InMemoryRateLimiter limiter = limiter(3, Duration.ofMinutes(1));

        assertThat(limiter.tryConsume("user").remainingPermits()).isEqualTo(2);
        assertThat(limiter.tryConsume("user").remainingPermits()).isEqualTo(1);
        assertThat(limiter.tryConsume("user").remainingPermits()).isZero();
    }

    @Test
    @DisplayName("keys are independent, so one user cannot exhaust another's quota")
    void keysAreIndependent() {
        InMemoryRateLimiter limiter = limiter(1, Duration.ofMinutes(1));

        assertThat(limiter.tryConsume("alice").allowed()).isTrue();
        assertThat(limiter.tryConsume("alice").allowed()).isFalse();
        assertThat(limiter.tryConsume("bob").allowed()).isTrue();
    }

    @Test
    @DisplayName("refills continuously rather than resetting on a window boundary")
    void refillsContinuously() {
        InMemoryRateLimiter limiter = limiter(6, Duration.ofMinutes(1));

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.tryConsume("user").allowed()).isTrue();
        }
        assertThat(limiter.tryConsume("user").allowed()).isFalse();

        // A sixth of the window has passed, which is worth exactly one token.
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        assertThat(limiter.tryConsume("user").allowed()).isFalse();
    }

    @Test
    @DisplayName("does not permit a double burst across a window boundary")
    void noDoubleBurstAcrossBoundary() {
        // The failure mode of a fixed-window counter: spend the quota at the end of one window
        // and again at the start of the next, for 2x the intended rate over a few seconds.
        InMemoryRateLimiter limiter = limiter(5, Duration.ofMinutes(1));

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryConsume("user").allowed()).isTrue();
        }

        clock.advance(Duration.ofSeconds(1));

        int allowedImmediatelyAfter = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.tryConsume("user").allowed()) {
                allowedImmediatelyAfter++;
            }
        }

        assertThat(allowedImmediatelyAfter)
                .as("a fixed-window limiter would have allowed all 5 again")
                .isZero();
    }

    @Test
    @DisplayName("caps refill at capacity, so idle time does not bank unlimited permits")
    void refillIsCapped() {
        InMemoryRateLimiter limiter = limiter(3, Duration.ofMinutes(1));

        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        clock.advance(Duration.ofHours(5));

        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        assertThat(limiter.tryConsume("user").allowed()).isTrue();
        assertThat(limiter.tryConsume("user").allowed()).isFalse();
    }

    @Test
    @DisplayName("a refusal says how long to wait, and the wait is honest")
    void retryAfterIsUsable() {
        InMemoryRateLimiter limiter = limiter(2, Duration.ofMinutes(1));

        limiter.tryConsume("user");
        limiter.tryConsume("user");
        RateLimiter.Decision refused = limiter.tryConsume("user");

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30));

        clock.advance(refused.retryAfter());
        assertThat(limiter.tryConsume("user").allowed())
                .as("waiting the advertised duration must actually work")
                .isTrue();
    }

    @Test
    @DisplayName("disabling the limiter lets everything through")
    void disabledAllowsEverything() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter(
                new RateLimitProperties(1, Duration.ofMinutes(1), false), clock);

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryConsume("user").allowed()).isTrue();
        }
    }

    @Test
    @DisplayName("applies documented defaults when properties are absent")
    void defaults() {
        RateLimitProperties defaults = new RateLimitProperties(null, null, null);

        assertThat(defaults.submissionsPerWindow()).isEqualTo(10);
        assertThat(defaults.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.enabled()).isTrue();
    }
}
