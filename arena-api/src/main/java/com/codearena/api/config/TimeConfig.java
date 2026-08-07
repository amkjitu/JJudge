package com.codearena.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A {@link Clock} bean so token expiry logic is testable.
 *
 * <p>Everything that reasons about "now" - access token expiry, refresh rotation, rate-limit
 * windows - takes this rather than calling {@code Instant.now()} directly, which is what lets a
 * test advance time by an hour instead of sleeping for one.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
