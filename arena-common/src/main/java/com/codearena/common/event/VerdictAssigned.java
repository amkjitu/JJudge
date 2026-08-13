package com.codearena.common.event;

import com.codearena.common.domain.JudgingMethod;
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
 * @param judgedBy       whether the code was actually run or the verdict was simulated. Null on
 *                       events published before this field existed - the topic is durable, so a
 *                       consumer meets old shapes after any deploy, and treating null as
 *                       {@code EXECUTED} would assert something about them that is not known
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
        Instant judgedAt,
        JudgingMethod judgedBy
) {

    /** Keyed by submission id for the same ordering reason as {@link SubmissionCreated}. */
    public String partitionKey() {
        return String.valueOf(submissionId);
    }
}
