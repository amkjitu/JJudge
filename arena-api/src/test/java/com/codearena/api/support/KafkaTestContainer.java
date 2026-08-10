package com.codearena.api.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Kafka broker for the whole test run, matching the Postgres and Redis containers.
 *
 * <p>The {@code apache/kafka} image runs in KRaft mode, so there is no ZooKeeper container to
 * start and wait for - which halves the startup cost of every test that needs a broker.
 *
 * <p>A real broker rather than {@code EmbeddedKafka}: the things worth testing here are
 * serialization across a module boundary, partition assignment and offset commits, and an
 * in-process stub reproduces the API without reproducing the behaviour.
 */
public final class KafkaTestContainer {

    private static final KafkaContainer INSTANCE =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static {
        INSTANCE.start();
    }

    private KafkaTestContainer() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", INSTANCE::getBootstrapServers);
    }

    public static String bootstrapServers() {
        return INSTANCE.getBootstrapServers();
    }
}
