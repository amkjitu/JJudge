package com.codearena.common.event;

import com.codearena.common.domain.Language;

import java.time.Instant;

/**
 * Published when a submission is accepted; consumed by the judge worker.
 *
 * <h2>Why the source code travels in the event</h2>
 *
 * <p>The obvious alternative is to send only {@code submissionId} and have the worker fetch the
 * code from shared storage. That is the right design once such storage exists - it keeps the log
 * small and avoids a second copy of a large payload - but today the source lives in the API's own
 * process, which the worker cannot reach. Phase 7 moves it to MongoDB, at which point this field
 * can become a reference.
 *
 * <p>Carrying it is safe rather than merely convenient: the submission endpoint caps source at
 * 64 KiB, comfortably inside Kafka's 1 MB default message limit, so the payload is bounded by
 * validation rather than by hope.
 *
 * @param submissionId the row this verdict will eventually be written back to
 * @param userId       denormalised so the worker never has to query the API's database
 * @param problemId    likewise
 * @param timeLimitMs  the problem's limits travel with the work, so judging needs no lookups
 * @param submittedAt  when the user submitted, for end-to-end latency measurement
 */
public record SubmissionCreated(
        Long submissionId,
        Long userId,
        Long problemId,
        String problemSlug,
        Language language,
        String sourceCode,
        Integer timeLimitMs,
        Integer memoryLimitMb,
        Instant submittedAt
) {

    /**
     * Partition key. Keyed by submission id so retries and duplicates of the same submission land
     * on the same partition and are therefore processed in order by a single consumer - two
     * workers judging one submission concurrently would race to write conflicting verdicts.
     */
    public String partitionKey() {
        return String.valueOf(submissionId);
    }
}
