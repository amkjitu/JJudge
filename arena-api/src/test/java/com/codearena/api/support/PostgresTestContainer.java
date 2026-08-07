package com.codearena.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL container for the entire test run.
 *
 * <p>The singleton-container pattern rather than {@code @Container}: the repository slice tests
 * and the full-context API tests both need a database, and starting one per test class would
 * add roughly two seconds each for no benefit. The JVM reaps it on exit, and Testcontainers'
 * Ryuk sidecar cleans up if the JVM is killed.
 */
public final class PostgresTestContainer {

    private static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("codearena")
                    .withUsername("codearena")
                    .withPassword("codearena");

    static {
        INSTANCE.start();
    }

    private PostgresTestContainer() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", INSTANCE::getUsername);
        registry.add("spring.datasource.password", INSTANCE::getPassword);
    }
}
