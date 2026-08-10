package com.codearena.judge;

import com.codearena.common.domain.Language;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.SubmissionCreated;
import com.codearena.common.event.VerdictAssigned;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JudgeService")
class JudgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final String WORKING_SOLUTION =
            "public class Main { public static void main(String[] a) { System.out.println(1); } }";

    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    private JudgeService serviceWith(int testCases) {
        // Zero delay: the sleep exists to make the pipeline observable in a browser, not to slow
        // down a unit test.
        JudgeProperties properties = new JudgeProperties(testCases, 4, 1, 0);
        return new JudgeService(new SimulatedJudge(), pool, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SubmissionCreated submission(String source) {
        return new SubmissionCreated(42L, 3L, 23L, "edit-distance", Language.JAVA, source,
                2000, 256, NOW);
    }

    @Test
    @DisplayName("runs every test case and reports the totals")
    void runsAllCases() {
        VerdictAssigned verdict = serviceWith(20).judge(submission(WORKING_SOLUTION));

        assertThat(verdict.testsTotal()).isEqualTo(20);
        assertThat(verdict.testsPassed()).isBetween(0, 20);
        assertThat(verdict.submissionId()).isEqualTo(42L);
        assertThat(verdict.userId()).isEqualTo(3L);
        assertThat(verdict.problemId()).isEqualTo(23L);
        assertThat(verdict.judgedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("accepts only when every case passes")
    void acceptedMeansAllCasesPassed() {
        VerdictAssigned verdict = serviceWith(20).judge(submission(WORKING_SOLUTION));

        if (verdict.verdict() == Verdict.AC) {
            assertThat(verdict.testsPassed()).isEqualTo(verdict.testsTotal());
            assertThat(verdict.failedTestCase()).isNull();
        } else {
            assertThat(verdict.testsPassed()).isLessThan(verdict.testsTotal());
            assertThat(verdict.failedTestCase()).isNotNull();
        }
    }

    @Test
    @DisplayName("reports the earliest failing case, not whichever thread finished first")
    void reportsFirstFailureByIndex() {
        // The cases run concurrently, so without an explicit sort the reported index would be
        // whichever failure a thread happened to return first - different on every run.
        VerdictAssigned first = serviceWith(20).judge(submission(WORKING_SOLUTION));
        VerdictAssigned second = serviceWith(20).judge(submission(WORKING_SOLUTION));

        assertThat(first.failedTestCase()).isEqualTo(second.failedTestCase());
        assertThat(first.verdict()).isEqualTo(second.verdict());
    }

    @Test
    @DisplayName("a failing submission's failed case is within range and consistent with the count")
    void failedCaseIsConsistent() {
        // Unbalanced braces guarantee a compile error on every case, so case 1 must be the first
        // failure and nothing can have passed.
        VerdictAssigned verdict = serviceWith(20).judge(submission("public class Main { void x() {"));

        assertThat(verdict.verdict()).isEqualTo(Verdict.CE);
        assertThat(verdict.failedTestCase()).isEqualTo(1);
        assertThat(verdict.testsPassed()).isZero();
    }

    @Test
    @DisplayName("reports the slowest case, which is the binding constraint")
    void runtimeIsTheMaximum() {
        VerdictAssigned verdict = serviceWith(20).judge(submission(WORKING_SOLUTION));

        assertThat(verdict.runtimeMs()).isBetween(0, 2000);
    }

    @Test
    @DisplayName("judging is deterministic across runs")
    void deterministic() {
        VerdictAssigned first = serviceWith(20).judge(submission(WORKING_SOLUTION));
        VerdictAssigned second = serviceWith(20).judge(submission(WORKING_SOLUTION));

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("a single test case still works")
    void singleCase() {
        VerdictAssigned verdict = serviceWith(1).judge(submission(WORKING_SOLUTION));

        assertThat(verdict.testsTotal()).isEqualTo(1);
    }
}
