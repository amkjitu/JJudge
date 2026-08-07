package com.codearena.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Token bucket held in process. Replaced by a Redis-backed implementation in Phase 5.
 *
 * <p>A token bucket rather than a fixed window because a fixed window lets a caller spend the
 * whole quota in the last second of one window and again in the first second of the next -
 * twice the intended burst across the boundary. The bucket refills continuously, so the rate
 * holds wherever the requests land.
 *
 * <p>Buckets are keyed by user id and never swept. That is bounded by the number of users who
 * have ever submitted rather than by request volume, and a bucket is two fields; the Redis
 * implementation gets expiry for free.
 *
 * <p><strong>Known limitation:</strong> the count is per JVM, so two replicas behind a load
 * balancer permit twice the configured rate. That is exactly why this sits behind
 * {@link RateLimiter} rather than being called directly.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private final RateLimitProperties properties;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InMemoryRateLimiter(RateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Decision tryConsume(String key) {
        if (!properties.enabled()) {
            return Decision.allowed(properties.submissionsPerWindow());
        }

        int capacity = properties.submissionsPerWindow();
        double tokensPerNano = (double) capacity / properties.window().toNanos();
        Instant now = clock.instant();

        AtomicReference<Decision> decision = new AtomicReference<>();

        // compute() holds the bin lock for this key, making the whole read-refill-consume-write
        // sequence atomic without a lock of our own.
        buckets.compute(key, (ignored, existing) -> {
            double available = existing == null
                    ? capacity
                    : Math.min(capacity,
                    existing.tokens() + Duration.between(existing.lastRefill(), now).toNanos() * tokensPerNano);

            if (available >= 1.0) {
                double remaining = available - 1.0;
                decision.set(Decision.allowed((int) Math.floor(remaining)));
                return new Bucket(remaining, now);
            }

            long nanosUntilNextToken = (long) Math.ceil((1.0 - available) / tokensPerNano);
            decision.set(Decision.denied(Duration.ofNanos(Math.max(nanosUntilNextToken, 1))));
            return new Bucket(available, now);
        });

        return decision.get();
    }

    /** Visible for tests: forgets every bucket. */
    void reset() {
        buckets.clear();
    }

    private record Bucket(double tokens, Instant lastRefill) {
    }
}
