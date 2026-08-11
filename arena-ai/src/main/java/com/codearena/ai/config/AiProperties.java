package com.codearena.ai.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the model-backed half of the service.
 *
 * @param enabled       whether to call a model at all. Off turns the service into its heuristic
 *                      analyser alone, which is a legitimate way to run it - and the only way to
 *                      run it on a machine that cannot host a model.
 * @param timeout       how long to wait for a model before giving up and answering heuristically.
 *                      A hint that arrives after the user has moved on is worth nothing, so this
 *                      is short by design.
 * @param maxSourceChars source longer than this is rejected rather than sent. Prompt cost scales
 *                      with input, and a 200 KB paste is not a solution anybody wants explained.
 */
@ConfigurationProperties(prefix = "arena.ai")
public record AiProperties(boolean enabled,
                           Duration timeout,
                           @Positive int maxSourceChars) {

    public AiProperties {
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        maxSourceChars = maxSourceChars <= 0 ? 20_000 : maxSourceChars;
    }
}
