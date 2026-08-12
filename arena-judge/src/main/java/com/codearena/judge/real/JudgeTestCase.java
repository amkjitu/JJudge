package com.codearena.judge.real;

/**
 * One test case as the judge needs it: an input, the output it must produce, and where it sits
 * in the order so a failure can be reported as a case number.
 *
 * <p>Separate from the MongoDB document it is loaded from. The engine takes plain values and
 * knows nothing about Spring Data, which is what makes it testable without a database.
 */
public record JudgeTestCase(int index, String input, String expectedOutput) {
}
