package com.codearena.judge.sandbox;

/**
 * The sandbox itself could not be used - no daemon, missing image, container would not start.
 *
 * <p>Distinct from a submission failing, and deliberately so. A submission that crashes is an
 * RTE and a normal outcome; a sandbox that cannot start is the judge being broken, and reporting
 * it as RTE would blame the user for the operator's problem. The listener lets this propagate so
 * Kafka retries rather than recording a wrong verdict.
 */
public class SandboxUnavailableException extends RuntimeException {

    public SandboxUnavailableException(String message) {
        super(message);
    }

    public SandboxUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
