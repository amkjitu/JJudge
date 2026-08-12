package com.codearena.judge.sandbox;

import java.time.Duration;
import java.util.List;

/**
 * One command to run inside a sandbox.
 *
 * @param command      argv, executed without a shell - a shell would let a crafted filename or
 *                     argument become a second command
 * @param stdin        fed to the process verbatim; empty for a compile step
 * @param wallClock    hard limit on elapsed time. Wall clock rather than CPU time, because a
 *                     program that sleeps, blocks on input or deadlocks consumes no CPU and
 *                     would never hit a CPU limit
 * @param memoryBytes  container memory limit; exceeding it is a kill, reported as MLE
 * @param maxOutputBytes stdout is truncated past this. A submission that prints an infinite loop
 *                     of output would otherwise fill the judge's memory before the wall clock
 *                     ever expired
 */
public record ExecutionRequest(List<String> command,
                               String stdin,
                               Duration wallClock,
                               long memoryBytes,
                               long maxOutputBytes) {

    public ExecutionRequest {
        command = List.copyOf(command);
        stdin = stdin == null ? "" : stdin;
    }
}
