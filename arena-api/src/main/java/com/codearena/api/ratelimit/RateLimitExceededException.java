package com.codearena.api.ratelimit;

import java.time.Duration;

/**
 * Raised when a caller has spent its quota. Mapped to 429 with a {@code Retry-After} header.
 */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Rate limit exceeded; retry in " + retryAfter.toSeconds() + "s");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
