package com.codearena.judge;

import com.codearena.common.event.ArenaTopics;
import com.codearena.common.event.SubmissionCreated;
import com.codearena.common.event.VerdictAssigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes work and publishes results.
 *
 * <p>The method returns only once the verdict has been published, so the offset is committed
 * after the work is durable rather than before it starts. Combined with keying both topics by
 * submission id - which puts every message about one submission on one partition - a submission
 * is judged exactly once in the absence of failure, and at least once in its presence. The API
 * side is idempotent to cover the "at least".
 */
@Component
public class SubmissionListener {

    private static final Logger log = LoggerFactory.getLogger(SubmissionListener.class);

    private final JudgeService judgeService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public SubmissionListener(JudgeService judgeService, KafkaTemplate<String, Object> kafkaTemplate) {
        this.judgeService = judgeService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(
            topics = ArenaTopics.SUBMISSIONS,
            groupId = "arena-judge",
            concurrency = "${arena.judge.consumers:2}")
    public void onSubmission(SubmissionCreated submission) {
        log.info("Received submission {} for problem '{}'",
                submission.submissionId(), submission.problemSlug());

        VerdictAssigned verdict = judgeService.judge(submission);

        // Blocking on the send is deliberate. Returning before the broker has acknowledged the
        // verdict would let Kafka commit the submission offset while the result is still only in
        // a local buffer - lose the process there and the submission is judged, forgotten, and
        // never retried.
        kafkaTemplate.send(ArenaTopics.VERDICTS, verdict.partitionKey(), verdict)
                .join();
    }
}
