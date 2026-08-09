package com.codearena.api.recommendation;

import java.util.Comparator;

/**
 * A candidate with its score attached.
 *
 * <p>{@link #BY_SCORE_THEN_ID} breaks ties on problem id rather than leaving them to the heap's
 * arbitrary ordering. Two problems with identical scores are common - the seeded catalogue has
 * several - and without a total order the same request could return a different list each time,
 * which makes both the UI and the tests non-deterministic for no benefit.
 */
public record ScoredCandidate(Candidate candidate, ScoreBreakdown breakdown) {

    public static final Comparator<ScoredCandidate> BY_SCORE_THEN_ID =
            Comparator.comparingDouble((ScoredCandidate s) -> s.breakdown().total())
                    .thenComparingLong(s -> s.candidate().problemId());

    public double total() {
        return breakdown.total();
    }
}
