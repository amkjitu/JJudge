package com.codearena.api.service;

/**
 * Shared constants for proficiency scoring, so the profile endpoint and the recommendation
 * engine (Phase 5) cannot disagree about what "weak at dp" means.
 */
public final class ProficiencyScoring {

    /**
     * Bayesian pseudo-count {@code k} in {@code solved / (attempts + k)}.
     *
     * <p>Chosen as 3 because most tags in the seeded catalogue carry 2-6 problems: at k=3 a
     * user needs roughly three clean solves before a tag reads as comfortable, which stops a
     * single lucky solve from removing a topic from the recommendation pool.
     */
    public static final double DEFAULT_SMOOTHING = 3.0;

    private ProficiencyScoring() {
    }
}
