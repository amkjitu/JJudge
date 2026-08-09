package com.codearena.api.recommendation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Engine beans.
 *
 * <p>Declared in a plain {@code @Configuration} rather than as {@code @Component}s, for the same
 * reason the rate limiter is: slice tests do not scan {@code @Configuration}, so a
 * {@code @WebMvcTest} of an unrelated controller does not have to satisfy the engine's
 * dependencies. The engine itself is framework-free - this class is the only place Spring and
 * the algorithm meet.
 */
@Configuration
@EnableConfigurationProperties(RecommendationProperties.class)
public class RecommendationConfig {

    @Bean
    public ProblemScorer problemScorer(RecommendationProperties properties) {
        return new ProblemScorer(properties);
    }

    @Bean
    public RecommendationEngine recommendationEngine(ProblemScorer scorer,
                                                     RecommendationProperties properties) {
        return new RecommendationEngine(scorer, properties);
    }
}
