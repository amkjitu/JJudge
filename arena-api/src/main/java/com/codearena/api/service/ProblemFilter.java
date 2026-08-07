package com.codearena.api.service;

import com.codearena.common.domain.Difficulty;

/**
 * Query criteria for the problem catalogue. Every field is optional; a null means "do not
 * restrict on this".
 */
public record ProblemFilter(
        String tag,
        Difficulty difficulty,
        Integer minRating,
        Integer maxRating,
        String search
) {

    public static ProblemFilter none() {
        return new ProblemFilter(null, null, null, null, null);
    }
}
