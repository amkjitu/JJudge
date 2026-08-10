package com.codearena.judge;

import com.codearena.common.domain.Verdict;

/**
 * The result of running one test case.
 *
 * @param index    1-based position, so a failure can be reported as "test 4 of 20"
 * @param passed   whether the case produced the expected output within its limits
 * @param verdict  null when passed; otherwise why it failed
 * @param runtimeMs how long the case took
 */
public record TestCaseOutcome(int index, boolean passed, Verdict verdict, int runtimeMs) {

    public static TestCaseOutcome passed(int index, int runtimeMs) {
        return new TestCaseOutcome(index, true, null, runtimeMs);
    }

    public static TestCaseOutcome failed(int index, Verdict verdict, int runtimeMs) {
        return new TestCaseOutcome(index, false, verdict, runtimeMs);
    }
}
