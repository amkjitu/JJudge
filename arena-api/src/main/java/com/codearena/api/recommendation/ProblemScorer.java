package com.codearena.api.recommendation;

import java.time.Duration;
import java.time.Instant;

/**
 * The scoring function.
 *
 * <pre>
 *   score = w₁·tagWeakness + w₂·ratingFit + w₃·recency − w₄·repetitionPenalty
 * </pre>
 *
 * <p>Each term is normalised to [0, 1] before weighting. That normalisation is the point: it
 * means the weights are directly comparable, so "weakness matters more than recency" is
 * expressed by {@code 0.45 > 0.10} and not by an accident of units. A term measured in rating
 * points and another in days would make the weights meaningless.
 *
 * <p>Pure and stateless - no clock of its own, no configuration lookup, no collaborators beyond
 * the values passed in.
 */
public final class ProblemScorer {

    private final RecommendationProperties properties;

    public ProblemScorer(RecommendationProperties properties) {
        this.properties = properties;
    }

    public ScoreBreakdown score(Candidate candidate,
                                int userRating,
                                TagProficiency proficiency,
                                Instant now) {

        double tagWeakness = proficiency.weaknessAcross(candidate.tags());
        double ratingFit = ratingFit(candidate.rating(), userRating);
        double recency = recency(candidate.createdAt(), now);
        double repetitionPenalty = repetitionPenalty(candidate.priorAttempts());

        double total = properties.weightTagWeakness() * tagWeakness
                + properties.weightRatingFit() * ratingFit
                + properties.weightRecency() * recency
                - properties.weightRepetitionPenalty() * repetitionPenalty;

        return new ScoreBreakdown(tagWeakness, ratingFit, recency, repetitionPenalty, total);
    }

    /**
     * A Gaussian centred a little <em>above</em> the user's rating, in [0, 1].
     *
     * <p>Gaussian rather than a linear ramp because fit should fall away gently near the peak
     * and sharply far from it: a problem 30 points off target is nearly as good, one 400 points
     * off is not "a bit worse", it is the wrong problem. A linear function cannot express both.
     */
    double ratingFit(int problemRating, int userRating) {
        double target = userRating + properties.ratingStretch();
        double spread = properties.ratingSpread();
        double delta = problemRating - target;
        return Math.exp(-(delta * delta) / (2.0 * spread * spread));
    }

    /**
     * Exponential decay with a configurable half-life, in (0, 1].
     *
     * <p>Half-life rather than a linear age penalty so the term never goes negative and never
     * hits zero: an old problem should be mildly less attractive, not disqualified.
     */
    double recency(Instant createdAt, Instant now) {
        if (createdAt == null || createdAt.isAfter(now)) {
            return 1.0;
        }
        double ageDays = Duration.between(createdAt, now).toSeconds() / 86_400.0;
        return Math.pow(0.5, ageDays / properties.recencyHalfLifeDays());
    }

    /**
     * Saturating penalty {@code n / (n + s)}, in [0, 1).
     *
     * <p>Candidates are unsolved, so prior attempts are prior failures. The first couple of
     * failures barely register - people bounce off a problem and come back - but a wall hit
     * five times is one the user should be steered away from rather than towards. Saturating
     * rather than linear so the tenth failure does not dominate every other term.
     */
    double repetitionPenalty(int priorAttempts) {
        if (priorAttempts <= 0) {
            return 0.0;
        }
        return priorAttempts / (priorAttempts + properties.repetitionSoftening());
    }
}
