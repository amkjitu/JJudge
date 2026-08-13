package com.codearena.judge;

import com.codearena.common.domain.JudgingMethod;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.SubmissionCreated;
import com.codearena.common.event.VerdictAssigned;
import com.codearena.judge.real.JudgeTestCase;
import com.codearena.judge.real.SandboxedJudgeEngine;
import com.codearena.judge.real.TestCaseSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Runs a submission's test cases and reduces them to a verdict.
 *
 * <h2>Where the concurrency is, and where it is not</h2>
 *
 * <p>The test cases of one submission are run on a thread pool, because they are independent and
 * overlapping them is the difference between 20 × 40ms and roughly a fifth of that.
 *
 * <p>Throughput <em>across</em> submissions deliberately does not use a pool. The Kafka listener
 * processes one record to completion before returning, and parallelism comes from consuming
 * several partitions concurrently. Handing each record to an executor and returning immediately
 * would let Kafka commit the offset before the work finished - so a crash mid-judge would lose
 * the submission silently, and at-least-once delivery would quietly become at-most-once. The
 * broker's own concurrency model already solves this; an application-level queue in front of it
 * only breaks the guarantee.
 */
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);

    private final SimulatedJudge simulatedJudge;
    private final ExecutorService testCasePool;
    private final JudgeProperties properties;
    private final Clock clock;
    private final Optional<SandboxedJudgeEngine> sandboxedEngine;
    private final Optional<TestCaseSource> testCaseSource;

    public JudgeService(SimulatedJudge simulatedJudge,
                        ExecutorService testCasePool,
                        JudgeProperties properties,
                        Clock clock,
                        Optional<SandboxedJudgeEngine> sandboxedEngine,
                        Optional<TestCaseSource> testCaseSource) {
        this.simulatedJudge = simulatedJudge;
        this.testCasePool = testCasePool;
        this.properties = properties;
        this.clock = clock;
        this.sandboxedEngine = sandboxedEngine;
        this.testCaseSource = testCaseSource;
    }

    /** What one attempt produced, and how. Paired because the verdict is not readable without it. */
    private record JudgingRun(List<TestCaseOutcome> outcomes, JudgingMethod method) {
    }

    public VerdictAssigned judge(SubmissionCreated submission) {
        long startedAt = System.nanoTime();
        JudgingRun run = executeOrSimulate(submission);
        List<TestCaseOutcome> outcomes = run.outcomes();

        // Ordered by index so "first failure" means the earliest case, not whichever thread
        // happened to finish first. Without this the reported failing case would vary between
        // runs of identical input.
        //
        // Sorted into a new list rather than in place: an engine is entitled to return an
        // immutable one, and the sandboxed engine does for a compile error - a single CE outcome
        // built with List.of. Sorting that threw UnsupportedOperationException, so every
        // submission that failed to compile took the judge down instead of being reported.
        outcomes = outcomes.stream()
                .sorted(Comparator.comparingInt(TestCaseOutcome::index))
                .toList();

        TestCaseOutcome firstFailure = outcomes.stream()
                .filter(outcome -> !outcome.passed())
                .findFirst()
                .orElse(null);

        int testsPassed = (int) outcomes.stream().filter(TestCaseOutcome::passed).count();
        Verdict verdict = firstFailure == null ? Verdict.AC : firstFailure.verdict();

        // Runtime of the slowest case, which is what a competitive judge reports - the binding
        // constraint is the worst case, not the average.
        int runtimeMs = outcomes.stream().mapToInt(TestCaseOutcome::runtimeMs).max().orElse(0);

        log.info("Judged submission {} as {} ({}/{} cases, {} ms, wall {} ms, {})",
                submission.submissionId(), verdict, testsPassed, outcomes.size(), runtimeMs,
                (System.nanoTime() - startedAt) / 1_000_000, run.method());

        return new VerdictAssigned(
                submission.submissionId(),
                submission.userId(),
                submission.problemId(),
                verdict,
                runtimeMs,
                testsPassed,
                outcomes.size(),
                firstFailure == null ? null : firstFailure.index(),
                clock.instant(),
                run.method());
    }

    /**
     * Runs the submission for real when that is configured and possible, and simulates otherwise.
     *
     * <p>The fallback is not a nicety. Only some problems have test cases written, and a
     * submission to one that does not cannot be judged - but leaving it QUEUED for ever is a
     * worse answer than a simulated verdict, and refusing to start would take the whole worker
     * down over one unprepared problem.
     *
     * <p>So it degrades, and it says so. Which path ran travels on the event and ends up beside
     * the verdict in the database, because a simulated {@code WA} and an earned one are otherwise
     * identical to everyone downstream - and the reasonable assumption on seeing a verdict is the
     * stronger of the two.
     */
    private JudgingRun executeOrSimulate(SubmissionCreated submission) {
        if (!properties.judgesForReal() || sandboxedEngine.isEmpty() || testCaseSource.isEmpty()) {
            return new JudgingRun(runAllCases(submission), JudgingMethod.SIMULATED);
        }

        List<JudgeTestCase> cases = testCaseSource.get().findFor(submission.problemSlug());
        if (cases.isEmpty()) {
            log.warn("No test cases for problem '{}'; simulating submission {} instead of "
                            + "executing it. Add cases for this problem to judge it for real.",
                    submission.problemSlug(), submission.submissionId());
            return new JudgingRun(runAllCases(submission), JudgingMethod.SIMULATED);
        }

        return new JudgingRun(sandboxedEngine.get().run(submission, cases),
                JudgingMethod.EXECUTED);
    }

    private List<TestCaseOutcome> runAllCases(SubmissionCreated submission) {
        List<Future<TestCaseOutcome>> futures = new ArrayList<>(properties.testCases());
        for (int caseIndex = 1; caseIndex <= properties.testCases(); caseIndex++) {
            int index = caseIndex;
            futures.add(testCasePool.submit(() -> {
                sleepForSimulatedWork();
                return simulatedJudge.runTestCase(submission, index);
            }));
        }

        List<TestCaseOutcome> outcomes = new ArrayList<>(futures.size());
        for (Future<TestCaseOutcome> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (InterruptedException e) {
                // Restore the flag and abandon: something is shutting the worker down, and
                // swallowing the interrupt would leave the pool unable to stop.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while judging submission "
                        + submission.submissionId(), e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("Test case failed to run for submission "
                        + submission.submissionId(), e.getCause());
            }
        }
        return outcomes;
    }

    private void sleepForSimulatedWork() {
        try {
            Thread.sleep(properties.caseDelayMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
