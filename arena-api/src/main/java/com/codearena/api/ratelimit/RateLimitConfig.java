package com.codearena.api.ratelimit;

import com.codearena.api.service.CurrentUserProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

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

    @Bean
    public RateLimiter rateLimiter(RateLimitProperties properties, Clock clock) {
        return new InMemoryRateLimiter(properties, clock);
    }

    @Bean
    public SubmissionRateLimitInterceptor submissionRateLimitInterceptor(
            RateLimiter rateLimiter, CurrentUserProvider currentUserProvider) {
        return new SubmissionRateLimitInterceptor(rateLimiter, currentUserProvider);
    }
}
