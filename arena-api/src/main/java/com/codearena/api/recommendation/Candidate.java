package com.codearena.api.recommendation;

import java.time.Instant;
import java.util.Set;

/**
 * A problem the engine may recommend, reduced to exactly the fields scoring needs.
 *
 * <p>Deliberately not the JPA entity. The engine is pure: it takes plain values, so its tests
 * need no database, no Spring context and no fixtures beyond a constructor call - which is what
 * makes it practical to test the scoring behaviour exhaustively rather than incidentally.
 *
 * @param priorAttempts how many times this user has already attempted the problem. Candidates
 *                      are unsolved by construction, so a non-zero value means past failures.
 */
public record Candidate(
        long problemId,
        int rating,
        Set<String> tags,
        Instant createdAt,
        int priorAttempts
) {
}
