package com.codearena.api.config;

import com.codearena.common.event.ArenaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * Three partitions: the unit of consumer parallelism. A topic with one partition can only
     * ever be served by one consumer in a group, so the judge could not scale out no matter how
     * many replicas were started.
     *
     * <p>Declared by the API because it is the producer and boots first; the judge sets
     * {@code missing-topics-fatal: false} so it can start in either order.
     *
     * <p>Replication factor 1 is a single-broker development choice, not a recommendation.
     */
    @Bean
    public NewTopic submissionsTopic() {
        return TopicBuilder.name(ArenaTopics.SUBMISSIONS).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic verdictsTopic() {
        return TopicBuilder.name(ArenaTopics.VERDICTS).partitions(3).replicas(1).build();
    }

    /**
     * Retry a failing verdict twice, then move on.
     *
     * <p>The default is infinite redelivery, which stops the partition dead: one unprocessable
     * verdict and every submission behind it is never written back, with nothing but a repeating
     * log line to show for it.
     */
    @Bean
    public CommonErrorHandler verdictErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "Giving up on verdict at offset {} of {}-{}; the submission stays queued",
                        record.offset(), record.topic(), record.partition(), exception),
                new FixedBackOff(1_000L, 2L));

        // A malformed message will never become well-formed; retrying it only adds latency.
        handler.addNotRetryableExceptions(DeserializationException.class);
        return handler;
    }
}
