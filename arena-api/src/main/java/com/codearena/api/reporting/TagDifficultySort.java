package com.codearena.api.reporting;

/**
 * Whitelist of sortable columns for the tag-difficulty report.
 *
 * <p>{@code ORDER BY} cannot be parameterised in JDBC - a bind variable is only legal where a
 * value is expected, not an identifier. Interpolating a user-supplied string there is a
 * textbook injection hole, so the API accepts an enum name and the SQL fragment is chosen from
 * this fixed set. Nothing from the request ever reaches the query as text.
 */
public enum TagDifficultySort {

    TAG("t.name ASC"),
    PROBLEM_COUNT("problem_count DESC, t.name ASC"),
    SUBMISSIONS("total_submissions DESC, t.name ASC"),
    SOLVERS("distinct_solvers DESC, t.name ASC"),
    /** Hardest topics first; untouched tags sort last rather than first. */
    HARDEST("(accepted_submissions::numeric / NULLIF(total_submissions, 0)) ASC NULLS LAST, t.name ASC");

    private final String orderByClause;

    TagDifficultySort(String orderByClause) {
        this.orderByClause = orderByClause;
    }

    String orderByClause() {
        return orderByClause;
    }
}
