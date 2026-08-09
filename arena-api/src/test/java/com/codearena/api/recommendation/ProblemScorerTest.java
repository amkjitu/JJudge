package com.codearena.api.recommendation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("ProblemScorer")
class ProblemScorerTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private final RecommendationProperties defaults = defaultProperties();
    private final ProblemScorer scorer = new ProblemScorer(defaults);

    private static RecommendationProperties defaultProperties() {
        return new RecommendationProperties(null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static TagProficiency proficiencyOf(Map<String, int[]> counts) {
        Map<String, TagProficiency.TagRecord> records = new java.util.HashMap<>();
        counts.forEach((tag, sa) -> records.put(tag, new TagProficiency.TagRecord(sa[0], sa[1])));
        return new TagProficiency(records, 3.0);
    }

    private static Candidate candidate(int rating, Set<String> tags, Instant createdAt, int attempts) {
        return new Candidate(1L, rating, tags, createdAt, attempts);
    }

    @Nested
    @DisplayName("rating fit")
    class RatingFit {

        @Test
        @DisplayName("peaks at the stretch point above the user's rating, not at their rating")
        void peaksAboveCurrentRating() {
            // Recommending what you can already do is comfortable and useless, so the peak sits
            // deliberately above the user.
            double atStretch = scorer.ratingFit(1300, 1200);
            double atRating = scorer.ratingFit(1200, 1200);

            assertThat(atStretch).isCloseTo(1.0, within(1e-9));
            assertThat(atRating).isLessThan(atStretch);
        }

        @Test
        @DisplayName("falls away symmetrically either side of the peak")
        void symmetricAroundPeak() {
            assertThat(scorer.ratingFit(1300 - 80, 1200))
                    .isCloseTo(scorer.ratingFit(1300 + 80, 1200), within(1e-9));
        }

        @Test
        @DisplayName("decays gently near the peak and sharply far from it")
        void gaussianShape() {
            double near = scorer.ratingFit(1330, 1200);   //  30 off target
            double mid = scorer.ratingFit(1450, 1200);    // 150 off target
            double far = scorer.ratingFit(1700, 1200);    // 400 off target

            assertThat(near).isGreaterThan(0.9);
            assertThat(mid).isBetween(0.2, 0.6);
            assertThat(far).isLessThan(0.01);
            // A linear ramp could not produce all three at once - that is the point of the
            // Gaussian.
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 800, 1200, 2000, 4000})
        @DisplayName("stays within [0, 1] across the whole rating range")
        void bounded(int problemRating) {
            assertThat(scorer.ratingFit(problemRating, 1200)).isBetween(0.0, 1.0);
        }
    }

    @Nested
    @DisplayName("recency")
    class Recency {

        @Test
        @DisplayName("a brand-new problem scores 1")
        void newProblemScoresOne() {
            assertThat(scorer.recency(NOW, NOW)).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("halves at the configured half-life")
        void halvesAtHalfLife() {
            Instant created = NOW.minus(Duration.ofDays(defaults.recencyHalfLifeDays()));

            assertThat(scorer.recency(created, NOW)).isCloseTo(0.5, within(1e-6));
        }

        @Test
        @DisplayName("decays towards zero without ever reaching it")
        void neverDisqualifies() {
            double ancient = scorer.recency(NOW.minus(Duration.ofDays(3650)), NOW);

            assertThat(ancient).isGreaterThan(0.0).isLessThan(0.01);
        }

        @Test
        @DisplayName("a missing or future creation date is treated as brand new, not as an error")
        void toleratesOddDates() {
            assertThat(scorer.recency(null, NOW)).isEqualTo(1.0);
            assertThat(scorer.recency(NOW.plus(Duration.ofDays(1)), NOW)).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("repetition penalty")
    class Repetition {

        @Test
        @DisplayName("is zero for a problem never attempted")
        void zeroForFreshProblem() {
            assertThat(scorer.repetitionPenalty(0)).isZero();
            assertThat(scorer.repetitionPenalty(-1)).isZero();
        }

        @Test
        @DisplayName("grows with failures but saturates below 1")
        void saturates() {
            double one = scorer.repetitionPenalty(1);
            double five = scorer.repetitionPenalty(5);
            double fifty = scorer.repetitionPenalty(50);

            assertThat(one).isLessThan(five).isLessThan(fifty);
            assertThat(fifty).isLessThan(1.0);
            // The first bounce off a problem barely registers; a wall hit five times does.
            assertThat(one).isLessThan(0.4);
            assertThat(five).isGreaterThan(0.6);
        }
    }

    @Nested
    @DisplayName("total score")
    class Total {

        @Test
        @DisplayName("prefers a weak topic over a strong one, all else equal")
        void weaknessDominates() {
            TagProficiency proficiency = proficiencyOf(Map.of("dp", new int[]{0, 10}, "arrays", new int[]{9, 10}));

            double weakTopic = scorer.score(
                    candidate(1300, Set.of("dp"), NOW, 0), 1200, proficiency, NOW).total();
            double strongTopic = scorer.score(
                    candidate(1300, Set.of("arrays"), NOW, 0), 1200, proficiency, NOW).total();

            assertThat(weakTopic).isGreaterThan(strongTopic);
        }

        @Test
        @DisplayName("an untouched topic scores as maximally weak")
        void untouchedTopicLooksWeak() {
            TagProficiency proficiency = proficiencyOf(Map.of("arrays", new int[]{9, 10}));

            ScoreBreakdown untouched = scorer.score(
                    candidate(1300, Set.of("geometry"), NOW, 0), 1200, proficiency, NOW);

            assertThat(untouched.tagWeakness()).isCloseTo(1.0, within(1e-9));
        }

        @Test
        @DisplayName("repeated failures push a problem down the ranking")
        void repetitionSubtracts() {
            TagProficiency proficiency = proficiencyOf(Map.of("dp", new int[]{1, 7}));

            double fresh = scorer.score(
                    candidate(1300, Set.of("dp"), NOW, 0), 1200, proficiency, NOW).total();
            double failedRepeatedly = scorer.score(
                    candidate(1300, Set.of("dp"), NOW, 6), 1200, proficiency, NOW).total();

            assertThat(failedRepeatedly).isLessThan(fresh);
        }

        @Test
        @DisplayName("the breakdown reconstructs the total exactly")
        void breakdownIsConsistent() {
            TagProficiency proficiency = proficiencyOf(Map.of("dp", new int[]{1, 2}));
            ScoreBreakdown b = scorer.score(
                    candidate(1350, Set.of("dp"), NOW.minus(Duration.ofDays(90)), 2),
                    1200, proficiency, NOW);

            double recomputed = defaults.weightTagWeakness() * b.tagWeakness()
                    + defaults.weightRatingFit() * b.ratingFit()
                    + defaults.weightRecency() * b.recency()
                    - defaults.weightRepetitionPenalty() * b.repetitionPenalty();

            assertThat(b.total()).isCloseTo(recomputed, within(1e-12));
        }

        @Test
        @DisplayName("every component is normalised to [0, 1], which is what makes weights comparable")
        void componentsAreNormalised() {
            TagProficiency proficiency = proficiencyOf(Map.of("dp", new int[]{1, 2}));
            ScoreBreakdown b = scorer.score(
                    candidate(2400, Set.of("dp", "math"), NOW.minus(Duration.ofDays(500)), 9),
                    1200, proficiency, NOW);

            assertThat(b.tagWeakness()).isBetween(0.0, 1.0);
            assertThat(b.ratingFit()).isBetween(0.0, 1.0);
            assertThat(b.recency()).isBetween(0.0, 1.0);
            assertThat(b.repetitionPenalty()).isBetween(0.0, 1.0);
        }

        @Test
        @DisplayName("weakness across several topics is the mean, not the worst")
        void weaknessIsTheMean() {
            TagProficiency proficiency = proficiencyOf(
                    Map.of("dp", new int[]{0, 10}, "arrays", new int[]{100, 100}));

            double dpOnly = scorer.score(
                    candidate(1300, Set.of("dp"), NOW, 0), 1200, proficiency, NOW).tagWeakness();
            double arraysOnly = scorer.score(
                    candidate(1300, Set.of("arrays"), NOW, 0), 1200, proficiency, NOW).tagWeakness();
            double both = scorer.score(
                    candidate(1300, Set.of("dp", "arrays"), NOW, 0), 1200, proficiency, NOW).tagWeakness();

            // Asserting the property rather than a magic number: smoothing means proficiency
            // never reaches exactly 1, so any hard-coded midpoint would be a lie about the maths.
            assertThat(both).isCloseTo((dpOnly + arraysOnly) / 2, within(1e-9));
            // Taking the worst tag would score this at dpOnly and make every problem touching
            // one unfamiliar topic look maximally urgent.
            assertThat(both).isLessThan(dpOnly);
        }
    }
}
