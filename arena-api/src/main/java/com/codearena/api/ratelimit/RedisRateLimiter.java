package com.codearena.api.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * Token bucket held in Redis, so the limit is shared across every application replica.
 *
 * <p>Same algorithm as {@link InMemoryRateLimiter} deliberately: swapping where the state lives
 * should not change how the limiter behaves. The difference is that this one counts once for
 * the whole deployment, where the in-process version counts per JVM and therefore permits N
 * times the configured rate across N replicas.
 *
 * <p>The refill-and-consume step runs as a Lua script - see {@code token-bucket.lua} for why it
 * cannot be a sequence of ordinary commands.
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /**
     * Namespace only. What the limit is <em>for</em> is the caller's business - the interceptor
     * already passes {@code submissions:<user>} - so adding "submissions" here too produced
     * {@code ratelimit:submissions:submissions:alice}. Consistent, and therefore invisible to
     * every test, but wrong the moment a second limit exists.
     */
    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redis;
    private final RedisScript<List> tokenBucketScript;
    private final RateLimitProperties properties;

    public RedisRateLimiter(StringRedisTemplate redis,
                            RedisScript<List> tokenBucketScript,
                            RateLimitProperties properties) {
        this.redis = redis;
        this.tokenBucketScript = tokenBucketScript;
        this.properties = properties;
    }

    @Override
    public Decision tryConsume(String key) {
        int capacity = properties.submissionsPerWindow();
        if (!properties.enabled()) {
            return Decision.allowed(capacity);
        }

        double refillPerMs = (double) capacity / properties.window().toMillis();
        // Two windows of idle time is long enough for any bucket to have refilled completely,
        // so anything older carries no information worth keeping.
        long ttlMs = properties.window().toMillis() * 2;

        try {
            List<?> result = redis.execute(
                    tokenBucketScript,
                    List.of(KEY_PREFIX + key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerMs),
                    String.valueOf(ttlMs));

            if (result == null || result.size() < 3) {
                return failOpen(key, "unexpected script result: " + result);
            }

            boolean allowed = toLong(result.get(0)) == 1L;
            int remaining = (int) toLong(result.get(1));
            long retryAfterMs = toLong(result.get(2));

            return allowed
                    ? Decision.allowed(remaining)
                    : Decision.denied(Duration.ofMillis(Math.max(retryAfterMs, 1)));

        } catch (DataAccessException e) {
            return failOpen(key, e.getMessage());
        }
    }

    /**
     * Redis being unreachable must not stop people submitting solutions.
     *
     * <p>Fail-open is the right trade here and it is a judgement call worth stating: this
     * limiter protects the judge queue from enthusiasm, not the application from attack. An
     * outage that turned every submission into a 429 would convert a cache problem into a total
     * outage of the product's main action. A limiter guarding authentication or payment would
     * warrant the opposite choice.
     */
    private Decision failOpen(String key, String reason) {
        log.warn("Rate limiter unavailable for key '{}', allowing the request: {}", key, reason);
        return Decision.allowed(properties.submissionsPerWindow());
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
