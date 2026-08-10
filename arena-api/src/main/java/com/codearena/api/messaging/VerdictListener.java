package com.codearena.api.messaging;

import com.codearena.api.service.VerdictService;
import com.codearena.api.sse.SubmissionStream;
import com.codearena.api.web.mapper.SubmissionMapper;
import com.codearena.common.event.ArenaTopics;
import com.codearena.common.event.VerdictAssigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes verdicts from the judge, applies them, and pushes the result to any browser watching.
 *
 * <p>The database write and the SSE push are separated on purpose: {@link VerdictService#apply}
 * owns the transaction and returns only if something actually changed, and the notification
 * happens after it has returned. Pushing from inside the transaction would let a browser be told
 * about a verdict that then rolled back.
 */
@Component
public class VerdictListener {

    private static final Logger log = LoggerFactory.getLogger(VerdictListener.class);

    private final VerdictService verdictService;
    private final SubmissionStream submissionStream;
    private final SubmissionMapper submissionMapper;

    public VerdictListener(VerdictService verdictService,
                           SubmissionStream submissionStream,
                           SubmissionMapper submissionMapper) {
        this.verdictService = verdictService;
        this.submissionStream = submissionStream;
        this.submissionMapper = submissionMapper;
    }

    @KafkaListener(
            topics = ArenaTopics.VERDICTS,
            groupId = "arena-api",
            concurrency = "${arena.kafka.verdict-consumers:1}")
    public void onVerdict(VerdictAssigned event) {
        log.debug("Verdict {} for submission {}", event.verdict(), event.submissionId());

        // Empty means a duplicate or a submission that no longer exists - in both cases there is
        // nothing new to tell anyone, and re-publishing would push a verdict the browser has
        // already been given.
        verdictService.apply(event)
                .map(submissionMapper::toResponse)
                .ifPresent(submissionStream::publish);
    }
}
