package com.codearena.judge;

import com.codearena.common.domain.Language;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.SubmissionCreated;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Decides what a single test case does.
 *
 * <h2>Simulated, and deterministic on purpose</h2>
 *
 * <p>Actually compiling and running untrusted code needs a sandbox - containers, seccomp, cgroups
 * for the memory limit, a pid cap - which is a project in its own right and not what this one is
 * demonstrating. What is real here is everything around it: the queue, the worker, the ordering
 * guarantees, the write-back and the live update.
 *
 * <p>The outcome is a pure function of the submission's content, never of {@code Random} or the
 * clock. That is what makes the pipeline testable end to end: a test can submit a known string
 * and assert an exact verdict, rather than retrying until it happens to see one. It also means a
 * user who resubmits identical code gets the same answer, which is what anyone would expect of a
 * judge.
 *
 * <p>Recognisable patterns are checked before the hash so the behaviour is explainable rather
 * than arbitrary: an empty body cannot compile, an obvious infinite loop times out.
 */
public class SimulatedJudge {

    /** Below this, there is not enough code for a working solution in any language. */
    private static final int MINIMUM_PLAUSIBLE_LENGTH = 20;

    /** Roughly the share of submissions that pass, for code with no obvious tell. */
    private static final int ACCEPT_PERCENTILE = 70;

    /**
     * Evaluates one test case.
     *
     * @param caseIndex 1-based; part of the hash so different cases can disagree, which is what
     *                  makes "passed 7 of 20" possible rather than all-or-nothing
     */
    public TestCaseOutcome runTestCase(SubmissionCreated submission, int caseIndex) {
        String source = submission.sourceCode() == null ? "" : submission.sourceCode().strip();

        if (source.length() < MINIMUM_PLAUSIBLE_LENGTH || !hasBalancedBraces(source)) {
            // Compilation fails on the submission as a whole, so it is not case-specific.
            return TestCaseOutcome.failed(caseIndex, Verdict.CE, 0);
        }

        if (looksLikeInfiniteLoop(source)) {
            return TestCaseOutcome.failed(caseIndex, Verdict.TLE, submission.timeLimitMs());
        }

        int roll = hash(source, submission.submissionId(), caseIndex) % 100;
        int runtimeMs = estimateRuntime(submission, roll);

        if (roll < ACCEPT_PERCENTILE) {
            return TestCaseOutcome.passed(caseIndex, runtimeMs);
        }
        if (roll < 88) {
            return TestCaseOutcome.failed(caseIndex, Verdict.WA, runtimeMs);
        }
        if (roll < 96) {
            return TestCaseOutcome.failed(caseIndex, Verdict.TLE, submission.timeLimitMs());
        }
        return TestCaseOutcome.failed(caseIndex, Verdict.RTE, runtimeMs);
    }

    /**
     * A crude but honest proxy for compilability: unbalanced braces are the one syntax error
     * that can be detected without a parser, and it makes the CE verdict reachable from a test
     * with an obviously broken input rather than only by hash coincidence.
     */
    private boolean hasBalancedBraces(String source) {
        int depth = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private boolean looksLikeInfiniteLoop(String source) {
        String normalised = source.replaceAll("\\s+", "");
        return normalised.contains("while(true)")
                || normalised.contains("while(1)")
                || normalised.contains("whileTrue:")
                || normalised.contains("for(;;)");
    }

    /**
     * Interpreted languages are given a plausible handicap, so the runtimes on the submissions
     * page do not all look identical.
     */
    private int estimateRuntime(SubmissionCreated submission, int roll) {
        int base = 20 + (roll * submission.timeLimitMs()) / 400;
        double multiplier = switch (submission.language()) {
            case CPP -> 0.6;
            case JAVA, GO -> 1.0;
            case JAVASCRIPT -> 1.6;
            case PYTHON -> 2.4;
        };
        return Math.max(1, Math.min((int) (base * multiplier), submission.timeLimitMs()));
    }

    /**
     * CRC32 rather than {@code String.hashCode()}: the JDK's string hash is specified, but it
     * clusters badly for short similar inputs, and adjacent test-case indices would produce
     * adjacent buckets - so a submission would tend to pass or fail every case together.
     */
    private int hash(String source, long submissionId, int caseIndex) {
        CRC32 crc = new CRC32();
        crc.update(source.getBytes(StandardCharsets.UTF_8));
        crc.update((byte) (submissionId & 0xFF));
        crc.update((byte) caseIndex);
        return (int) (crc.getValue() % Integer.MAX_VALUE);
    }
}
