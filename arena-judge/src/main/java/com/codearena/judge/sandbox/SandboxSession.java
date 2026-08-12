package com.codearena.judge.sandbox;

/**
 * An isolated workspace, alive for one submission.
 *
 * <p>Scoped rather than a single {@code run(command)} call because judging is several commands
 * over shared state: write the source, compile it once, then run the compiled program against
 * every test case. Re-creating the isolation per test case would mean recompiling per test case.
 *
 * <p>{@link AutoCloseable} because the workspace holds real resources - a container, a
 * filesystem - and leaking one per submission would exhaust the host within a day. Closing is
 * expected to succeed even if the sandbox is already gone; a cleanup that throws on an
 * already-dead container turns a finished judgement into a failed one.
 */
public interface SandboxSession extends AutoCloseable {

    /**
     * Places a file in the workspace before anything runs.
     *
     * <p>Separate from {@link #run} because the source arrives as a string and writing it via a
     * command would mean quoting it into a shell - the one place where a crafted submission
     * could break out of its own argument and run something else.
     */
    void writeFile(String relativePath, String content);

    /** Runs one command to completion, or to its wall-clock limit. */
    ExecutionResult run(ExecutionRequest request);

    /** Destroys the workspace. Never throws. */
    @Override
    void close();
}
