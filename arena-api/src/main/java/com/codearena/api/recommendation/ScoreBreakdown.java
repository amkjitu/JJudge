package com.codearena.api.recommendation;

/**
 * The individual terms behind one problem's score, kept alongside the total.
 *
 * <p>Carried through to the API rather than discarded because a recommender that cannot explain
 * itself is indistinguishable from a random shuffle - to a user, and to anyone debugging it. It
 * is also what lets a test assert <em>why</em> a problem ranked where it did instead of only
 * that it did.
 *
 * <p>All four components are in [0, 1] before weighting, which is what makes the weights
 * directly comparable to each other.
 */
public record ScoreBreakdown(
        double tagWeakness,
        double ratingFit,
        double recency,
        double repetitionPenalty,
        double total
) {
}
