package com.codearena.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Redis container for the entire test run, matching {@link PostgresTestContainer}.
 *
 * <p>A real Redis rather than an embedded fake: the rate limiter's correctness lives in a Lua
 * script, and a mock would test the Java around it while skipping the part that actually
 * decides whether a request is allowed. Sorted-set semantics are likewise something to verify
 * against the real implementation rather than against an approximation of it.
 */
public final class RedisTestContainer {

    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> INSTANCE =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--appendonly", "no", "--save", "");

    static {
        INSTANCE.start();
    }

    private RedisTestContainer() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", INSTANCE::getHost);
        registry.add("spring.data.redis.port", () -> INSTANCE.getMappedPort(REDIS_PORT));
    }
}
