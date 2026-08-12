package com.codearena.judge.sandbox;

/**
 * Runs untrusted code somewhere it cannot do harm.
 *
 * <p>An interface because the isolation mechanism is a deployment decision, not an architectural
 * one. The Docker implementation here suits a single machine; a hosted judge would want
 * microVMs or gVisor, and neither the judging logic nor its tests should have to change for
 * that.
 *
 * <p>It also makes the guarantees testable. {@code SandboxIT} asserts that a fork bomb, a memory
 * bomb, a network call and a filesystem write all fail - claims about a sandbox are worth
 * exactly as much as the attempts made to break it.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <ol>
 *   <li>No network access of any kind.</li>
 *   <li>No writable filesystem outside a small, size-capped scratch area.</li>
 *   <li>A memory ceiling that kills rather than swaps.</li>
 *   <li>A process-count ceiling, so a fork bomb exhausts its own limit and nothing else.</li>
 *   <li>A wall-clock kill that fires even when the process consumes no CPU.</li>
 *   <li>No privileges: unprivileged user, all capabilities dropped, no way to acquire more.</li>
 * </ol>
 */
public interface Sandbox {

    /**
     * Prepares an isolated workspace, runs the given steps in order, and destroys it.
     *
     * <p>Steps share the workspace so a compile can produce a binary the run steps use, and are
     * abandoned as soon as one fails - there is no point running test cases against a program
     * that did not compile.
     */
    SandboxSession open(String workspaceName);
}
