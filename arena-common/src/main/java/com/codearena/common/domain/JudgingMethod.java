package com.codearena.common.domain;

/**
 * How a verdict was actually reached.
 *
 * <p>The judge can produce a verdict two ways, and they are not equally meaningful. Without this
 * recorded alongside the verdict, the two are indistinguishable once written to the database - a
 * simulated {@code WA} looks exactly like one earned by running the code and comparing output,
 * and anyone reading the submission would reasonably assume the stronger of the two.
 *
 * <p>Recording it is the cheap half of the fix. The expensive half - writing test cases for every
 * problem - is worth doing, but until it is done the platform should say which it did rather than
 * let the ambiguity ride.
 */
public enum JudgingMethod {

    /** Compiled and run against the problem's real test cases in a sandbox. */
    EXECUTED,

    /**
     * Derived from a hash of the submission. Deterministic and instant, and says nothing whatever
     * about whether the code is correct. This is what a problem with no test cases falls back to.
     */
    SIMULATED
}
