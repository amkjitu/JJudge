package com.codearena.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * What happens when judging a record throws.
 *
 * <p>Without an explicit handler, Spring Kafka retries the same record indefinitely: the
 * partition never advances, every submission behind the failing one is stuck, and the only
 * symptom is a log line repeating for ever. Two retries a second apart, then give up on that
 * record and move on.
 *
 * <p>"Give up" here means the submission stays {@code QUEUED} in the API - visible to the user
 * as unjudged rather than silently marked wrong. A dead-letter topic would be the next step in a
 * real deployment; a single-record poison pill blocking the queue is the failure worth fixing
 * first.
 */
@Configuration
public class KafkaErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorConfig.class);

    @Bean
    public CommonErrorHandler judgeErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "Giving up on offset {} of {}-{} after retries; the submission stays queued",
                        record.offset(), record.topic(), record.partition(), exception),
                new FixedBackOff(1_000L, 2L));

        // A message that cannot be deserialised will never deserialise, so retrying it is pure
        // delay. Fail it immediately and let the partition move on.
        handler.addNotRetryableExceptions(DeserializationException.class);
        return handler;
    }
}
