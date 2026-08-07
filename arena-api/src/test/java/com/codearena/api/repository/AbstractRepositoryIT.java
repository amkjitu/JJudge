package com.codearena.api.repository;

import com.codearena.api.config.JpaAuditingConfig;
import com.codearena.api.support.PostgresTestContainer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for repository integration tests.
 *
 * <p>Flyway runs against the real database and Hibernate is left on {@code ddl-auto=validate},
 * so every one of these tests also asserts that the entity mappings still match the
 * migrations.
 *
 * <p>{@link JpaAuditingConfig} has to be imported explicitly: slice tests do not scan plain
 * {@code @Configuration} classes, so without this {@code @CreatedDate} never fires and every
 * insert fails the {@code NOT NULL} constraint on the audit columns.
 *
 * @see PostgresTestContainer for why the container is a shared singleton
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
abstract class AbstractRepositoryIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
    }
}
