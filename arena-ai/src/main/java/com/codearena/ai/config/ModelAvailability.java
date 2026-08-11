package com.codearena.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops asking a model that is not answering.
 *
 * <h2>Why a fallback alone is not enough</h2>
 *
 * <p>Falling back to static analysis makes a dead model <em>correct</em>. It does not make it
 * <em>fast</em>: every request still waits the full model timeout before giving up, so the common
 * deployment - the one with no Ollama running at all - pays twenty seconds for an answer that was
 * always going to come from the analyser.
 *
 * <p>That is not just slow, it is destabilising. The caller has its own read timeout, and a
 * service that reliably takes the model timeout plus overhead sits right at the edge of it: the
 * hint arrives, eventually, as a 503. Two timeouts chosen independently, each defensible, adding
 * up to a broken feature.
 *
 * <p>So consecutive failures open the circuit and requests skip the model entirely for a cooldown.
 * The first request after a restart still pays full price - that is the probe that discovers
 * whether a model is there - and everything after it is immediate.
 *
 * <h2>Deliberately not a full circuit breaker</h2>
 *
 * <p>No half-open state, no rolling failure window, no metrics. Resilience4j would give all three,
 * and none of them change the behaviour of the one case this exists for: a model that is either
 * present or absent for the whole life of the process. A counter and a timestamp are enough to say
 * so honestly.
 */
@Component
public class ModelAvailability {

    private static final Logger log = LoggerFactory.getLogger(ModelAvailability.class);

    /**
     * Two rather than one: a single timeout can be a cold model loading its weights, which is
     * worth waiting through once. Two in a row is a model that is not coming.
     */
    private static final int FAILURE_THRESHOLD = 2;

    private static final Duration COOLDOWN = Duration.ofMinutes(2);

    private final Clock clock;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public ModelAvailability(Clock clock) {
        this.clock = clock;
    }

    /** Whether the next request should attempt the model at all. */
    public boolean shouldTry() {
        Instant opened = openedAt.get();
        if (opened == null) {
            return true;
        }
        if (Duration.between(opened, clock.instant()).compareTo(COOLDOWN) < 0) {
            return false;
        }
        // Cooldown elapsed: let one request through to find out whether anything changed.
        openedAt.set(null);
        consecutiveFailures.set(0);
        log.info("Retrying the model after a {} cooldown", COOLDOWN);
        return true;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedAt.set(null);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD && openedAt.get() == null) {
            openedAt.set(clock.instant());
            log.warn("Model failed {} times in a row; skipping it for {} and answering from "
                    + "static analysis. Requests will be fast rather than slow-then-wrong.",
                    FAILURE_THRESHOLD, COOLDOWN);
        }
    }
}
