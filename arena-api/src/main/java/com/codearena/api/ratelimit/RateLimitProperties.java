package com.codearena.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param submissionsPerWindow how many submissions one user may make per window
 * @param window               the sliding window length
 * @param enabled              off in tests that are not about rate limiting
 */
@ConfigurationProperties(prefix = "arena.rate-limit")
public record RateLimitProperties(
        Integer submissionsPerWindow,
        Duration window,
        Boolean enabled
) {

    public RateLimitProperties {
        // Ten a minute is generous for a human solving problems and still low enough to stop a
        // script filling the judge queue.
        submissionsPerWindow = submissionsPerWindow == null ? 10 : submissionsPerWindow;
        window = window == null ? Duration.ofMinutes(1) : window;
        enabled = enabled == null || enabled;
    }
}
