package com.codearena.common.domain;

/**
 * Judge outcome for a submission. Null until the judge worker has finished evaluating.
 */
public enum Verdict {
    /** Accepted - all test cases passed within limits. */
    AC,
    /** Wrong answer on at least one test case. */
    WA,
    /** Time limit exceeded. */
    TLE,
    /** Runtime error. */
    RTE,
    /** Compilation error. */
    CE
}
