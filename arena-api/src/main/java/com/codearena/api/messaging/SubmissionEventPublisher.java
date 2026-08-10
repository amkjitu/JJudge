package com.codearena.api.messaging;

import com.codearena.common.event.ArenaTopics;
import com.codearena.common.event.SubmissionCreated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes newly accepted submissions to the judge queue.
 *
 * <h2>Publishing after commit, not during</h2>
 *
 * <p>The event is queued for send only once the surrounding database transaction commits.
 * Sending inline would create a race the wrong way round: the judge is fast enough to consume,
 * judge and publish a verdict before the API's transaction commits, so the verdict listener
 * would look for a submission row that does not exist yet and drop it. The submission would sit
 * on QUEUED for ever with no error anywhere.
 *
 * <p>The residual risk is the other direction - committed but not published, if the process dies
 * in the gap. That leaves a submission visibly stuck rather than silently wrong, and the honest
 * fix is a transactional outbox rather than a bigger try/catch. Noted here rather than pretended
 * away.
 */
@Component
public class SubmissionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SubmissionEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SubmissionEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(SubmissionCreated event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(event);
                }
            });
        } else {
            send(event);
        }
    }

    private void send(SubmissionCreated event) {
        kafkaTemplate.send(ArenaTopics.SUBMISSIONS, event.partitionKey(), event)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        // Not rethrown: the transaction has already committed, so there is
                        // nothing left to roll back and throwing here would only surface as an
                        // unhandled callback exception. The submission stays QUEUED and visible.
                        log.error("Failed to queue submission {} for judging",
                                event.submissionId(), failure);
                    } else {
                        log.debug("Queued submission {} on partition {}",
                                event.submissionId(), result.getRecordMetadata().partition());
                    }
                });
    }
}
