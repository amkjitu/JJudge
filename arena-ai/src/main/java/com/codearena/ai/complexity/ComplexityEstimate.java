package com.codearena.ai.complexity;

import java.util.List;

/**
 * What the static analyser concluded, and why.
 *
 * @param timeComplexity  big-O estimate, e.g. {@code O(n log n)}
 * @param spaceComplexity big-O estimate for auxiliary space
 * @param reasons         the observations behind the estimate, in the order they were made
 * @param caveat          the most relevant way this particular estimate could be wrong
 */
public record ComplexityEstimate(String timeComplexity,
                                 String spaceComplexity,
                                 List<String> reasons,
                                 String caveat) {

    public ComplexityEstimate {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
