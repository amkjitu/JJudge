package com.codearena.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param testCases       how many test cases each problem is judged against
 * @param workerThreads   size of the pool that runs those cases
 * @param consumers       how many partitions this worker consumes in parallel
 * @param caseDelayMillis artificial per-case cost, so the pipeline is observable rather than
 *                        instantaneous - a verdict that arrives in 2ms proves nothing about
 *                        whether the UI actually updates live
 */
@ConfigurationProperties(prefix = "arena.judge")
public record JudgeProperties(
        Integer testCases,
        Integer workerThreads,
        Integer consumers,
        Integer caseDelayMillis
) {

    public JudgeProperties {
        testCases = testCases == null ? 20 : testCases;
        // Four threads for twenty short cases. The pool exists to overlap the cases of one
        // submission, not to scale throughput - that comes from consuming more partitions.
        workerThreads = workerThreads == null ? 4 : workerThreads;
        consumers = consumers == null ? 2 : consumers;
        caseDelayMillis = caseDelayMillis == null ? 40 : caseDelayMillis;
    }
}
