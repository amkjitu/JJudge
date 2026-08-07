package com.codearena.api.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit tests - no Spring context, no database. These run under {@code mvn test} on a
 * machine with no Docker at all; the {@code *IT} suites need a daemon and run under
 * {@code mvn verify}.
 */
@DisplayName("UserTagStats proficiency")
class UserTagStatsTest {

    private static UserTagStats stats(int solved, int attempts) {
        return UserTagStats.builder()
                .id(new UserTagStatsId(1L, 1L))
                .solvedCount(solved)
                .attemptCount(attempts)
                .build();
    }

    @Test
    @DisplayName("is zero when nothing has been solved")
    void zeroWhenNothingSolved() {
        assertThat(stats(0, 5).proficiency(3.0)).isZero();
    }

    @Test
    @DisplayName("is zero, not NaN, for a tag the user has never touched")
    void neverTouchedIsZeroNotNaN() {
        double value = stats(0, 0).proficiency(3.0);

        assertThat(value).isZero();
        assertThat(Double.isNaN(value)).isFalse();
    }

    @Test
    @DisplayName("stays below 1 no matter how good the record is")
    void neverReachesOne() {
        assertThat(stats(100, 100).proficiency(3.0)).isLessThan(1.0);
    }

    @Nested
    @DisplayName("smoothing prior")
    class Smoothing {

        @Test
        @DisplayName("penalises a small sample against a large one with the same raw ratio")
        void smallSampleRanksBelowLargeSample() {
            double oneOutOfOne = stats(1, 1).proficiency(3.0);
            double twentyOutOfTwenty = stats(20, 20).proficiency(3.0);

            assertThat(oneOutOfOne).isLessThan(twentyOutOfTwenty);
        }

        @Test
        @DisplayName("larger k demands more evidence before a tag counts as mastered")
        void largerKIsMoreConservative() {
            UserTagStats record = stats(4, 5);

            assertThat(record.proficiency(10.0)).isLessThan(record.proficiency(1.0));
        }

        @ParameterizedTest(name = "{0} solved / {1} attempts with k={2} -> {3}")
        @CsvSource({
                "0,  0,  3.0, 0.0",
                "1,  1,  3.0, 0.25",
                "3,  4,  3.0, 0.4285714",
                "18, 20, 3.0, 0.7826087",
                "5,  5,  0.0, 1.0"
        })
        @DisplayName("computes solved / (attempts + k)")
        void formula(int solved, int attempts, double k, double expected) {
            assertThat(stats(solved, attempts).proficiency(k)).isCloseTo(expected, within(1e-6));
        }
    }

    @Nested
    @DisplayName("weakness ordering")
    class Weakness {

        @Test
        @DisplayName("orders a neglected tag as weaker than a practised one")
        void neglectedTagIsWeaker() {
            UserTagStats dp = stats(0, 4);
            UserTagStats arrays = stats(9, 10);

            assertThat(dp.proficiency(3.0)).isLessThan(arrays.proficiency(3.0));
        }
    }
}
