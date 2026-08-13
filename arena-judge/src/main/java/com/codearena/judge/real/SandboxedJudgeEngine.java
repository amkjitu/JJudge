package com.codearena.judge.real;

import com.codearena.judge.TestCaseOutcome;
import com.codearena.judge.sandbox.ExecutionRequest;
import com.codearena.judge.sandbox.ExecutionResult;
import com.codearena.judge.sandbox.Sandbox;
import com.codearena.judge.sandbox.SandboxProperties;
import com.codearena.judge.sandbox.SandboxSession;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.SubmissionCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Judges by actually compiling and running the submission.
 *
 * <h2>Sequential, not parallel</h2>
 *
 * <p>The simulated judge overlaps its cases across a thread pool, which is free when a "case" is
 * a hash. Here every case is a real process competing for the same CPU quota and the same memory
 * ceiling inside one container. Running them concurrently would make each one slower and the
 * <em>timing</em> unreliable - and timing is a verdict here, not a statistic. A submission that
 * passes alone and TLEs when three of its own cases run beside it is a judge that cannot be
 * trusted.
 *
 * <h2>Stops at the first failure</h2>
 *
 * <p>Once a case fails the verdict is decided, and every further case costs a container round
 * trip to learn something the user is not told. Competitive judges behave this way and it is
 * also the honest reading of {@code testsPassed}: "passed 3 of 20 before failing" rather than a
 * total that implies everything ran.
 *
 * <h2>The judge does not bill the submission for its own overhead</h2>
 *
 * <p>Every test case is a {@code docker exec} round trip, and that costs real time that has
 * nothing to do with the code being judged - on a busy host it can exceed the entire time limit.
 * Charging it to the submission produces the worst failure a judge has: a correct answer marked
 * TLE, sending its author off to optimise something that was never slow.
 *
 * <p>So the round trip is <em>measured</em> rather than assumed. Opening a session times a no-op
 * command in the same container, and that baseline is subtracted from every case. It is measured
 * because it is a property of the machine and its current load, not of this code - a constant
 * chosen here would be wrong on any host but the one it was picked on.
 */
public class SandboxedJudgeEngine {

    private static final Logger log = LoggerFactory.getLogger(SandboxedJudgeEngine.class);

    /** Stdout past this is truncated; a submission printing forever cannot exhaust the judge. */
    private static final long MAX_OUTPUT_BYTES = 8L * 1024 * 1024;

    /**
     * Ceiling on the measured round-trip allowance. A host thrashing badly enough to spend this
     * long starting a no-op is not one whose timings mean anything, and without a cap the judge
     * would quietly hand out minute-long budgets and call slow code correct.
     */
    private static final Duration MAX_OVERHEAD = Duration.ofSeconds(5);

    /** How many times the no-op is timed. The minimum is taken - see {@link #measureOverhead}. */
    private static final int OVERHEAD_SAMPLES = 3;

    private final Sandbox sandbox;
    private final SandboxProperties properties;

    public SandboxedJudgeEngine(Sandbox sandbox, SandboxProperties properties) {
        this.sandbox = sandbox;
        this.properties = properties;
    }

    /**
     * @param cases the problem's test cases, in order
     * @return one outcome per case attempted - fewer than {@code cases.size()} when it stopped
     *         early, and a single CE outcome when the submission did not compile
     */
    public List<TestCaseOutcome> run(SubmissionCreated submission, List<JudgeTestCase> cases) {
        Optional<LanguageToolchain> maybeToolchain =
                LanguageToolchain.forLanguage(submission.language());

        if (maybeToolchain.isEmpty()) {
            // Not CE: the submission may be perfectly valid, and blaming it for the judge's
            // missing toolchain would send someone looking for a bug that is not there.
            throw new UnsupportedLanguageException(submission.language());
        }
        LanguageToolchain toolchain = maybeToolchain.get();

        try (SandboxSession session = sandbox.open(String.valueOf(submission.submissionId()))) {
            session.writeFile(toolchain.sourceFile(), submission.sourceCode());

            if (toolchain.needsCompiling()) {
                ExecutionResult compiled = session.run(new ExecutionRequest(
                        toolchain.compile(), "", properties.compileTimeout(),
                        properties.memoryMegabytes() * 1024L * 1024, 64 * 1024));

                if (!compiled.succeeded()) {
                    log.debug("Submission {} failed to compile: {}",
                            submission.submissionId(), compiled.stderr().strip());
                    // Index 0: compilation is not a test case, and reporting it as case 1 would
                    // imply a case failed when none ran.
                    return List.of(TestCaseOutcome.failed(0, Verdict.CE,
                            (int) compiled.durationMillis()));
                }
            }

            return runCases(submission, toolchain, session, cases);
        }
    }

    /**
     * Times a no-op inside the container to learn what a round trip costs right now.
     *
     * <p>The minimum of several samples rather than the mean: the floor is the real cost of a
     * round trip, and every millisecond above it is contention from something else on the host.
     * Averaging would fold that contention into the allowance and hand it to the submission as
     * extra time, which is exactly the leniency this is meant to avoid.
     *
     * <p>{@code /bin/true} is busybox, always present, and does nothing - so what is measured is
     * the client, the daemon and process creation, with no work of its own mixed in.
     */
    private Duration measureOverhead(SandboxSession session) {
        long lowest = Long.MAX_VALUE;
        for (int sample = 0; sample < OVERHEAD_SAMPLES; sample++) {
            ExecutionResult probe = session.run(new ExecutionRequest(
                    List.of("/bin/true"), "", properties.dockerCommandTimeout(),
                    properties.memoryMegabytes() * 1024L * 1024, 4096));

            // A failed probe means the container is unhealthy, and the cases about to run will
            // say so far more clearly than a guess at the overhead would.
            if (probe.succeeded()) {
                lowest = Math.min(lowest, probe.durationMillis());
            }
        }
        if (lowest == Long.MAX_VALUE) {
            return Duration.ZERO;
        }
        Duration measured = Duration.ofMillis(lowest);
        return measured.compareTo(MAX_OVERHEAD) > 0 ? MAX_OVERHEAD : measured;
    }

    private List<TestCaseOutcome> runCases(SubmissionCreated submission,
                                           LanguageToolchain toolchain,
                                           SandboxSession session,
                                           List<JudgeTestCase> cases) {

        Duration overhead = measureOverhead(session);
        Duration limit = toolchain.effectiveTimeLimit(submission.timeLimitMs());

        // What the submission is actually given: its own budget, plus the round trip it is not
        // responsible for. Subtracting the same figure from the result is what keeps the two
        // consistent - a case is TLE exactly when its own work exceeded its own limit.
        Duration wallClock = limit.plus(overhead);

        log.debug("Submission {} judged with a {} ms limit ({} ms stated x{}) "
                        + "and a measured {} ms round trip",
                submission.submissionId(), limit.toMillis(), submission.timeLimitMs(),
                toolchain.timeLimitMultiplier(), overhead.toMillis());

        List<TestCaseOutcome> outcomes = new ArrayList<>(cases.size());

        for (JudgeTestCase testCase : cases) {
            ExecutionResult result = session.run(new ExecutionRequest(
                    toolchain.run(), testCase.input(), wallClock,
                    properties.memoryMegabytes() * 1024L * 1024, MAX_OUTPUT_BYTES));

            TestCaseOutcome outcome = classify(testCase, result, overhead);
            outcomes.add(outcome);

            if (!outcome.passed()) {
                log.debug("Submission {} failed case {} with {}",
                        submission.submissionId(), testCase.index(), outcome.verdict());
                break;
            }
        }
        return outcomes;
    }

    /**
     * Turns one execution into a verdict.
     *
     * <p>Order matters. A program that exceeds the time limit usually also produces incomplete
     * output, so checking output first would report WA for what is really TLE - and the user
     * would optimise nothing because they were told their answer was wrong rather than slow.
     *
     * <p>The runtime reported is the measured elapsed time less the round trip, so the number a
     * user sees is their program's, not the judge's. Clamped at zero because a round trip
     * measured a few milliseconds high on one sample and low on the next would otherwise produce
     * a negative runtime for a program that finished instantly.
     */
    private TestCaseOutcome classify(JudgeTestCase testCase, ExecutionResult result,
                                     Duration overhead) {
        int index = testCase.index();
        int runtime = (int) Math.max(0, result.durationMillis() - overhead.toMillis());

        if (result.timedOut()) {
            return TestCaseOutcome.failed(index, Verdict.TLE, runtime);
        }
        if (result.outOfMemory()) {
            return TestCaseOutcome.failed(index, Verdict.MLE, runtime);
        }
        if (result.exitCode() != 0) {
            return TestCaseOutcome.failed(index, Verdict.RTE, runtime);
        }
        if (result.outputTruncated()) {
            // It exited cleanly having written megabytes. Whatever the intended answer was, this
            // is not it, and the truncated text cannot be compared meaningfully.
            return TestCaseOutcome.failed(index, Verdict.WA, runtime);
        }
        return OutputComparator.matches(testCase.expectedOutput(), result.stdout())
                ? TestCaseOutcome.passed(index, runtime)
                : TestCaseOutcome.failed(index, Verdict.WA, runtime);
    }
}
