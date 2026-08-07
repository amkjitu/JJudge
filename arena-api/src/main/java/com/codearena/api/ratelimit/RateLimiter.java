package com.codearena.api.ratelimit;

import java.time.Duration;

/**
 * Storage-agnostic rate limiter.
 *
 * <p>The interface exists so Phase 5 can swap the in-process implementation for a Redis-backed
 * one without touching the call sites. That swap matters: an in-process limiter counts per
 * instance, so two replicas behind a load balancer allow twice the intended rate.
 */
public interface RateLimiter {

    /**
     * Attempts to consume one permit for {@code key}.
     *
     * @return the outcome, including how long to wait when the request is refused
     */
    Decision tryConsume(String key);

    /**
     * @param allowed          whether the caller may proceed
     * @param remainingPermits permits left in the current window
     * @param retryAfter       how long until a permit frees up; {@link Duration#ZERO} when allowed
     */
    record Decision(boolean allowed, int remainingPermits, Duration retryAfter) {

        public static Decision allowed(int remainingPermits) {
            return new Decision(true, remainingPermits, Duration.ZERO);
        }

        public static Decision denied(Duration retryAfter) {
            return new Decision(false, 0, retryAfter);
        }
    }
}
