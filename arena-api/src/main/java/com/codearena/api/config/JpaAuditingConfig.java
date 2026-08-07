package com.codearena.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables {@code @CreatedDate} population.
 *
 * <p>Deliberately a separate {@code @Configuration} rather than an annotation on the
 * application class. {@code @EnableJpaAuditing} registers {@code jpaAuditingHandler}, which
 * needs a populated JPA metamodel; on the application class it would be picked up by
 * {@code @WebMvcTest} slices too, and every one of them would fail to start with
 * "JPA metamodel must not be empty". Slice tests do not scan plain {@code @Configuration}
 * classes, so putting it here keeps the web slice free of a persistence dependency it has no
 * use for.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
