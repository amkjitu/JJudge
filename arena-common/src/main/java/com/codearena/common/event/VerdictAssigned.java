package com.codearena.common.event;

import com.codearena.common.domain.Verdict;

import java.time.Instant;

/**
 * Published by the judge worker when it finishes; consumed by the API, which writes the result
 * back and pushes it to any browser watching.
 *
 * @param runtimeMs      wall time of the slowest test case that ran
 * @param testsPassed    how many test cases passed before the verdict was decided
 * @param testsTotal     how many the problem has
 * @param failedTestCase 1-based index of the first failing case, or null for an accepted run.
 *                       Included because "wrong answer" without a location is not feedback.
 */
public record VerdictAssigned(
        Long submissionId,
        Long userId,
        Long problemId,
        Verdict verdict,
        Integer runtimeMs,
        Integer testsPassed,
        Integer testsTotal,
        Integer failedTestCase,
        Instant judgedAt
) {

    /** Keyed by submission id for the same ordering reason as {@link SubmissionCreated}. */
    public String partitionKey() {
        return String.valueOf(submissionId);
    }
}
