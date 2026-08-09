package com.codearena.api.ratelimit;

import com.codearena.api.service.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.List;

/**
 * Rate limiting beans.
 *
 * <p>Declared here rather than as {@code @Component}s on purpose. {@code @WebMvcTest} scans
 * {@code HandlerInterceptor} implementations - so a component-scanned
 * {@link SubmissionRateLimitInterceptor} gets instantiated in every controller slice, where the
 * plain-{@code @Component} {@link RateLimiter} it depends on is <em>not</em> scanned, and every
 * slice dies with "No qualifying bean of type RateLimiter".
 *
 * <p>Plain {@code @Configuration} classes are not scanned by slices, so putting both beans here
 * keeps them together in the full application and absent from slices, which is what a test that
 * is not about rate limiting wants anyway.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * Redis-backed when a Redis template exists, in-process otherwise.
     *
     * <p>Resolved through {@link ObjectProvider} inside one bean method rather than with two
     * beans and {@code @ConditionalOnBean}. That annotation only evaluates reliably in
     * auto-configuration, where Spring can guarantee ordering; in a user {@code @Configuration}
     * the answer depends on which definition happens to be registered first, which is a
     * coin-flip dressed up as configuration.
     */
    @Bean
    public RateLimiter rateLimiter(ObjectProvider<StringRedisTemplate> redisTemplate,
                                   ObjectProvider<RedisScript<List>> tokenBucketScript,
                                   RateLimitProperties properties,
                                   Clock clock) {
        StringRedisTemplate redis = redisTemplate.getIfAvailable();
        RedisScript<List> script = tokenBucketScript.getIfAvailable();

        if (redis != null && script != null) {
            log.info("Rate limiting submissions via Redis: {} per {}",
                    properties.submissionsPerWindow(), properties.window());
            return new RedisRateLimiter(redis, script, properties);
        }

        // Correct on a single instance, and permits N times the rate across N replicas - which
        // is why the log line says so rather than leaving it to be discovered in production.
        log.warn("No Redis available; rate limiting submissions in process. The limit is "
                + "per-instance and will not hold across replicas.");
        return new InMemoryRateLimiter(properties, clock);
    }

    @Bean
    public SubmissionRateLimitInterceptor submissionRateLimitInterceptor(
            RateLimiter rateLimiter, CurrentUserProvider currentUserProvider) {
        return new SubmissionRateLimitInterceptor(rateLimiter, currentUserProvider);
    }
}
