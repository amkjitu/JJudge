package com.codearena.judge;

import com.codearena.common.domain.Verdict;
import com.codearena.common.event.SubmissionCreated;
import com.codearena.common.event.VerdictAssigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public JudgeService(SimulatedJudge simulatedJudge,
                        ExecutorService testCasePool,
                        JudgeProperties properties,
                        Clock clock) {
        this.simulatedJudge = simulatedJudge;
        this.testCasePool = testCasePool;
        this.properties = properties;
        this.clock = clock;
    }

    public VerdictAssigned judge(SubmissionCreated submission) {
        long startedAt = System.nanoTime();
        List<TestCaseOutcome> outcomes = runAllCases(submission);

        // Ordered by index so "first failure" means the earliest case, not whichever thread
        // happened to finish first. Without this the reported failing case would vary between
        // runs of identical input.
        outcomes.sort(Comparator.comparingInt(TestCaseOutcome::index));

        TestCaseOutcome firstFailure = outcomes.stream()
                .filter(outcome -> !outcome.passed())
                .findFirst()
                .orElse(null);

        int testsPassed = (int) outcomes.stream().filter(TestCaseOutcome::passed).count();
        Verdict verdict = firstFailure == null ? Verdict.AC : firstFailure.verdict();

        // Runtime of the slowest case, which is what a competitive judge reports - the binding
        // constraint is the worst case, not the average.
        int runtimeMs = outcomes.stream().mapToInt(TestCaseOutcome::runtimeMs).max().orElse(0);

        log.info("Judged submission {} as {} ({}/{} cases, {} ms, wall {} ms)",
                submission.submissionId(), verdict, testsPassed, outcomes.size(), runtimeMs,
                (System.nanoTime() - startedAt) / 1_000_000);

        return new VerdictAssigned(
                submission.submissionId(),
                submission.userId(),
                submission.problemId(),
                verdict,
                runtimeMs,
                testsPassed,
                outcomes.size(),
                firstFailure == null ? null : firstFailure.index(),
                clock.instant());
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
