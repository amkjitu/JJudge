package com.codearena.ai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The circuit that stops a dead model costing every request its full timeout.
 *
 * <p>Driven by a movable clock rather than by sleeping: a two-minute cooldown asserted with a
 * real clock is a two-minute test, and one that would quietly stop testing the cooldown the
 * moment somebody shortened it.
 */
@DisplayName("ModelAvailability")
class ModelAvailabilityTest {

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final ModelAvailability availability = new ModelAvailability(clock);

    @Test
    @DisplayName("tries the model when nothing has gone wrong")
    void startsClosed() {
        assertThat(availability.shouldTry()).isTrue();
    }

    @Test
    @DisplayName("one failure is not enough to stop trying")
    void toleratesASingleFailure() {
        // A cold model loading several gigabytes of weights times out once and then works. That
        // is worth waiting through; giving up on it would be the wrong call.
        availability.recordFailure();

        assertThat(availability.shouldTry()).isTrue();
    }

    @Test
    @DisplayName("two consecutive failures stop further attempts")
    void opensAfterTwoFailures() {
        availability.recordFailure();
        availability.recordFailure();

        assertThat(availability.shouldTry()).isFalse();
    }

    @Test
    @DisplayName("a success in between resets the count")
    void successResets() {
        availability.recordFailure();
        availability.recordSuccess();
        availability.recordFailure();

        assertThat(availability.shouldTry())
                .as("failures either side of a success are not consecutive")
                .isTrue();
    }

    @Test
    @DisplayName("it stays closed for the whole cooldown")
    void staysOpenDuringCooldown() {
        availability.recordFailure();
        availability.recordFailure();

        clock.advance(Duration.ofSeconds(119));

        assertThat(availability.shouldTry()).isFalse();
    }

    @Test
    @DisplayName("after the cooldown one request probes the model again")
    void probesAfterCooldown() {
        availability.recordFailure();
        availability.recordFailure();

        clock.advance(Duration.ofMinutes(2).plusSeconds(1));

        assertThat(availability.shouldTry())
                .as("a model that was absent may have been started since")
                .isTrue();
    }

    @Test
    @DisplayName("a failed probe re-opens the circuit immediately rather than after two more")
    void failedProbeReopensAtOnce() {
        availability.recordFailure();
        availability.recordFailure();
        clock.advance(Duration.ofMinutes(3));

        // The probe is allowed through, and fails.
        assertThat(availability.shouldTry()).isTrue();
        availability.recordFailure();
        availability.recordFailure();

        assertThat(availability.shouldTry()).isFalse();
    }
}
