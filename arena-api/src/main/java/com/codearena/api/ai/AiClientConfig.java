package com.codearena.api.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HTTP client for arena-ai, with timeouts that are actually set.
 *
 * <p>The default {@code RestClient} has no read timeout at all, so a request to a service that
 * accepts the connection and then thinks forever holds a servlet thread indefinitely. Enough of
 * those and the API stops serving pages that have nothing to do with hints. That failure is far
 * more damaging than the missing hint, and it is invisible until it happens under load.
 *
 * <p>The read timeout is generous relative to a normal call and short relative to a page load,
 * because arena-ai already bounds its own model call - this is the outer guard for the case
 * where arena-ai itself is wedged.
 */
@Configuration
@EnableConfigurationProperties(AiClientConfig.AiClientProperties.class)
public class AiClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AiClientConfig.class);

    @ConfigurationProperties(prefix = "arena.ai-client")
    public record AiClientProperties(boolean enabled,
                                     String baseUrl,
                                     Duration connectTimeout,
                                     Duration readTimeout) {

        public AiClientProperties {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://arena-ai:8090" : baseUrl;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        }
    }

    /**
     * @return a client, or {@code null} when the integration is switched off. Callers take an
     *         {@code ObjectProvider} and treat absence as "no hints available", which is the
     *         same state as arena-ai being unreachable - so there is only one degraded path to
     *         reason about rather than two.
     */
    @Bean
    public AiClient aiClient(AiClientProperties properties) {
        if (!properties.enabled()) {
            log.info("arena.ai-client.enabled=false: hints and complexity analysis are disabled.");
            return null;
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());

        log.info("arena-ai integration enabled, calling {}", properties.baseUrl());

        return new AiClient(RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build());
    }
}
