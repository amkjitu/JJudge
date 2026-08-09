package com.codearena.api.recommendation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the recommendation engine, bound from {@code arena.recommendation.*}.
 *
 * <p>Every weight and threshold is configuration rather than a literal buried in the scorer, so
 * the behaviour can be tuned against real usage without a redeploy - and so tests can push a
 * single term to an extreme and assert that it alone changed the ordering.
 *
 * @param weightTagWeakness       pull towards topics the user is weak at
 * @param weightRatingFit         pull towards problems at the right difficulty
 * @param weightRecency           mild preference for newer problems, as a tie-breaker
 * @param weightRepetitionPenalty push away from problems already failed repeatedly
 * @param ratingBandBelow         how far below the user's rating to consider
 * @param ratingBandAbove         how far above; asymmetric because growth comes from stretch
 * @param ratingStretch           where difficulty fit peaks, relative to the user's rating
 * @param ratingSpread            how quickly fit falls away from the peak
 * @param recencyHalfLifeDays     problem age at which the recency term halves
 * @param repetitionSoftening     attempts needed for the penalty to reach half its maximum
 * @param masteryFloor            raw success rate at or above which a topic counts as learned
 * @param minEvidenceAttempts     attempts on a topic before the gate will judge it at all
 * @param maxPerTag               diversity cap: results sharing any one topic
 * @param overfetchFactor         how many extra candidates to keep so the cap can be satisfied
 */
@ConfigurationProperties(prefix = "arena.recommendation")
public record RecommendationProperties(
        Double weightTagWeakness,
        Double weightRatingFit,
        Double weightRecency,
        Double weightRepetitionPenalty,
        Integer ratingBandBelow,
        Integer ratingBandAbove,
        Integer ratingStretch,
        Integer ratingSpread,
        Integer recencyHalfLifeDays,
        Double repetitionSoftening,
        Double masteryFloor,
        Integer minEvidenceAttempts,
        Integer maxPerTag,
        Integer overfetchFactor
) {

    public RecommendationProperties {
        // Weakness dominates: the product's claim is "practise what you are bad at", and the
        // weights should make that literally true rather than merely aspirational.
        weightTagWeakness = orDefault(weightTagWeakness, 0.45);
        weightRatingFit = orDefault(weightRatingFit, 0.35);
        // Recency is a tie-breaker, not a driver. Weighted any higher it would start pushing
        // new problems ahead of well-matched ones.
        weightRecency = orDefault(weightRecency, 0.10);
        weightRepetitionPenalty = orDefault(weightRepetitionPenalty, 0.30);

        ratingBandBelow = orDefault(ratingBandBelow, 100);
        ratingBandAbove = orDefault(ratingBandAbove, 200);
        // Peak fit sits above the current rating: recommending what you can already do is
        // comfortable and useless.
        ratingStretch = orDefault(ratingStretch, 100);
        ratingSpread = orDefault(ratingSpread, 120);

        recencyHalfLifeDays = orDefault(recencyHalfLifeDays, 180);
        repetitionSoftening = orDefault(repetitionSoftening, 2.0);
        masteryFloor = orDefault(masteryFloor, 0.34);
        // Three attempts before the gate is willing to call a topic failed. Below that the
        // sample says more about how much someone has practised than about what they know.
        minEvidenceAttempts = orDefault(minEvidenceAttempts, 3);
        maxPerTag = orDefault(maxPerTag, 2);
        overfetchFactor = orDefault(overfetchFactor, 3);
    }

    private static Double orDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
