package com.codearena.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One MongoDB container for the entire test run, matching {@link PostgresTestContainer}.
 *
 * <p>Every full-context test registers this, not only the ones that read source code back.
 * Spring Boot auto-configures MongoDB the moment the starter is on the classpath, so a context
 * without a container still gets a client - pointed at {@code localhost:27017}, where nothing
 * is listening. Each call then blocks for the driver's server-selection timeout before failing,
 * which turns "this test does not care about Mongo" into "this test takes thirty seconds".
 * Pointing every context at a real container is both faster and more honest than mocking the
 * store out of contexts that happen not to exercise it.
 */
public final class MongoTestContainer {

    private static final MongoDBContainer INSTANCE =
            new MongoDBContainer(DockerImageName.parse("mongo:7"));

    static {
        INSTANCE.start();
    }

    private MongoTestContainer() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> INSTANCE.getReplicaSetUrl("codearena-test"));
    }
}
