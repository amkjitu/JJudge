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
 */
public class SandboxedJudgeEngine {

    private static final Logger log = LoggerFactory.getLogger(SandboxedJudgeEngine.class);

    /** Stdout past this is truncated; a submission printing forever cannot exhaust the judge. */
    private static final long MAX_OUTPUT_BYTES = 8L * 1024 * 1024;

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

    private List<TestCaseOutcome> runCases(SubmissionCreated submission,
                                           LanguageToolchain toolchain,
                                           SandboxSession session,
                                           List<JudgeTestCase> cases) {

        Duration limit = Duration.ofMillis(submission.timeLimitMs());
        List<TestCaseOutcome> outcomes = new ArrayList<>(cases.size());

        for (JudgeTestCase testCase : cases) {
            ExecutionResult result = session.run(new ExecutionRequest(
                    toolchain.run(), testCase.input(), limit,
                    properties.memoryMegabytes() * 1024L * 1024, MAX_OUTPUT_BYTES));

            TestCaseOutcome outcome = classify(testCase, result);
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
     */
    private TestCaseOutcome classify(JudgeTestCase testCase, ExecutionResult result) {
        int index = testCase.index();
        int runtime = (int) result.durationMillis();

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
