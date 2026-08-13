package com.codearena.judge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param mode            whether submissions are actually executed or their verdicts simulated
 * @param testCases       how many cases a simulated judgement invents. Ignored in REAL mode,
 *                        where the count is whatever the problem actually has
 * @param workerThreads   size of the pool that runs simulated cases
 * @param consumers       how many partitions this worker consumes in parallel
 * @param caseDelayMillis artificial per-case cost in SIMULATED mode, so the pipeline is
 *                        observable rather than instantaneous - a verdict that arrives in 2ms
 *                        proves nothing about whether the UI actually updates live
 */
@ConfigurationProperties(prefix = "arena.judge")
public record JudgeProperties(
        Mode mode,
        Integer testCases,
        Integer workerThreads,
        Integer consumers,
        Integer caseDelayMillis
) {

    public enum Mode {

        /**
         * Verdicts are a hash of the submission's text. Nothing is compiled or run, so nothing
         * untrusted executes anywhere - which is why this is the default and the only mode the
         * public deployment uses.
         */
        SIMULATED,

        /**
         * Submissions are compiled and executed in a sandbox.
         *
         * <p>Requires a reachable Docker daemon and the runner image. Enabling it means
         * arena-judge can create containers, and access to a Docker socket is equivalent to root
         * on the host that owns it - so turn this on only where that is acceptable.
         */
        REAL
    }

    public JudgeProperties {
        // SIMULATED by default, deliberately. Real execution is opt-in on every axis: it needs a
        // socket, an image, and someone to have decided both are acceptable.
        mode = mode == null ? Mode.SIMULATED : mode;
        testCases = testCases == null ? 20 : testCases;
        // Four threads for twenty short cases. The pool exists to overlap the cases of one
        // submission, not to scale throughput - that comes from consuming more partitions.
        workerThreads = workerThreads == null ? 4 : workerThreads;
        consumers = consumers == null ? 2 : consumers;
        caseDelayMillis = caseDelayMillis == null ? 40 : caseDelayMillis;
    }

    public boolean judgesForReal() {
        return mode == Mode.REAL;
    }
}
