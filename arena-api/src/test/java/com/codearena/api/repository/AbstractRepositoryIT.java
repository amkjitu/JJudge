package com.codearena.api.repository;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for repository integration tests.
 *
 * <p>Uses the singleton-container pattern rather than {@code @Container}: one PostgreSQL
 * instance is started on first class load and reused by every subclass, which turns a
 * per-class container start (~2s each) into a single one for the whole suite. The JVM
 * reaps it on exit, and Testcontainers' Ryuk sidecar cleans up if the JVM is killed.
 *
 * <p>Flyway runs against the real database and Hibernate is left on {@code ddl-auto=validate},
 * so every one of these tests also asserts that the entity mappings still match the
 * migrations.
 */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
abstract class AbstractRepositoryIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("codearena")
                    .withUsername("codearena")
                    .withPassword("codearena");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
