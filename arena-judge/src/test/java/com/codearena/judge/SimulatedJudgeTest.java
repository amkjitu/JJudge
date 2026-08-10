package com.codearena.judge;

import com.codearena.common.domain.Language;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.SubmissionCreated;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SimulatedJudge")
class SimulatedJudgeTest {

    private static final String WORKING_SOLUTION =
            "public class Main { public static void main(String[] a) { System.out.println(1); } }";

    private final SimulatedJudge judge = new SimulatedJudge();

    private static SubmissionCreated submission(String source) {
        return submission(source, Language.JAVA, 1L);
    }

    private static SubmissionCreated submission(String source, Language language, long id) {
        return new SubmissionCreated(id, 3L, 23L, "edit-distance", language, source,
                2000, 256, Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same submission always produces the same outcome")
        void repeatableForIdenticalInput() {
            TestCaseOutcome first = judge.runTestCase(submission(WORKING_SOLUTION), 7);
            TestCaseOutcome second = judge.runTestCase(submission(WORKING_SOLUTION), 7);

            // Determinism is what makes an end-to-end test able to assert an exact verdict
            // rather than retry until it sees one - and it is what a user expects when they
            // resubmit identical code.
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("different code can produce different outcomes")
        void sensitiveToSource() {
            Set<Boolean> results = new HashSet<>();
            for (int i = 0; i < 40; i++) {
                results.add(judge.runTestCase(
                        submission(WORKING_SOLUTION + " // variant " + i), 1).passed());
            }

            assertThat(results).as("a judge that always says the same thing is not a judge")
                    .hasSize(2);
        }

        @Test
        @DisplayName("test cases within one submission are judged independently")
        void casesDoNotMoveTogether() {
            Set<Boolean> results = new HashSet<>();
            for (int caseIndex = 1; caseIndex <= 40; caseIndex++) {
                results.add(judge.runTestCase(submission(WORKING_SOLUTION), caseIndex).passed());
            }

            // If the hash clustered by index, every case of a submission would agree and
            // "passed 7 of 20" could never happen.
            assertThat(results).hasSize(2);
        }
    }

    @Nested
    @DisplayName("recognisable failures")
    class RecognisableFailures {

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "int x;", "class A {}"})
        @DisplayName("code too short to be a solution fails to compile")
        void tooShortIsCompileError(String source) {
            assertThat(judge.runTestCase(submission(source), 1).verdict()).isEqualTo(Verdict.CE);
        }

        @Test
        @DisplayName("unbalanced braces fail to compile")
        void unbalancedBracesAreCompileError() {
            String broken = "public class Main { public static void main(String[] a) { ";

            assertThat(judge.runTestCase(submission(broken), 1).verdict()).isEqualTo(Verdict.CE);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "public class Main { void go() { while (true) { } } } // padding padding",
                "public class Main { void go() { for (;;) { } } } // padding padding padding",
                "public class Main { void go() { while(1) { } } } // padding padding padding"
        })
        @DisplayName("an obvious infinite loop times out")
        void infiniteLoopIsTimeLimitExceeded(String source) {
            TestCaseOutcome outcome = judge.runTestCase(submission(source), 1);

            assertThat(outcome.verdict()).isEqualTo(Verdict.TLE);
            assertThat(outcome.runtimeMs()).isEqualTo(2000);
        }

        @Test
        @DisplayName("a compile error reports no runtime")
        void compileErrorHasNoRuntime() {
            assertThat(judge.runTestCase(submission("x"), 1).runtimeMs()).isZero();
        }
    }

    @Nested
    @DisplayName("runtime")
    class Runtime {

        @Test
        @DisplayName("never exceeds the problem's time limit")
        void staysWithinTimeLimit() {
            for (int caseIndex = 1; caseIndex <= 50; caseIndex++) {
                assertThat(judge.runTestCase(submission(WORKING_SOLUTION), caseIndex).runtimeMs())
                        .isBetween(0, 2000);
            }
        }

        @Test
        @DisplayName("interpreted languages are slower than compiled ones")
        void languageAffectsRuntime() {
            int cpp = judge.runTestCase(submission(WORKING_SOLUTION, Language.CPP, 1L), 1).runtimeMs();
            int python = judge.runTestCase(submission(WORKING_SOLUTION, Language.PYTHON, 1L), 1).runtimeMs();

            assertThat(python).isGreaterThan(cpp);
        }
    }
}
