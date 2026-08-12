package com.codearena.judge.sandbox;

/**
 * What happened when a command ran.
 *
 * <p>{@code timedOut} and {@code outOfMemory} are separate from {@code exitCode} because both
 * present as a killed process, and a judge has to tell TLE from MLE from an ordinary crash. The
 * exit code alone cannot: a container killed for exceeding memory and one killed on the wall
 * clock both report 137.
 */
public record ExecutionResult(int exitCode,
                              String stdout,
                              String stderr,
                              long durationMillis,
                              boolean timedOut,
                              boolean outOfMemory,
                              boolean outputTruncated) {

    public boolean succeeded() {
        return exitCode == 0 && !timedOut && !outOfMemory;
    }
}
