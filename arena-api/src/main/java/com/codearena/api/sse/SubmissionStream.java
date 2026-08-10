package com.codearena.api.sse;

import com.codearena.api.web.dto.SubmissionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry of browsers waiting for a verdict.
 *
 * <h2>Why SSE rather than WebSocket</h2>
 *
 * <p>The traffic is one-directional and low-volume: the server has something to say, the browser
 * has nothing to send back. SSE is plain HTTP - it reconnects on its own, needs no handshake
 * upgrade, and passes through proxies that would need configuring for a WebSocket. A duplex
 * protocol would be strictly more machinery for a strictly smaller problem.
 *
 * <h2>Known limitation</h2>
 *
 * <p>Emitters live in this JVM's heap. With several API replicas, a verdict consumed by one
 * instance cannot reach a browser connected to another, and that browser waits until its poll
 * fallback or a reload. The fix is fanning verdicts out over Redis pub/sub so every instance can
 * serve any connection; it is not implemented here because there is one instance, and pretending
 * otherwise would be complexity without a reason. Stated rather than hidden.
 */
@Component
public class SubmissionStream {

    private static final Logger log = LoggerFactory.getLogger(SubmissionStream.class);

    /**
     * Long enough for a submission to be judged several times over, short enough that a
     * forgotten tab does not hold a connection for ever. On timeout the browser's EventSource
     * reconnects by itself, so the ceiling costs nothing.
     */
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    /**
     * Several emitters per submission because a user may have the page open in two tabs, and
     * copy-on-write because reads (one verdict fan-out) vastly outnumber writes (page loads).
     */
    private final Map<Long, List<SseEmitter>> emittersBySubmission = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long submissionId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());

        emittersBySubmission
                .computeIfAbsent(submissionId, id -> new CopyOnWriteArrayList<>())
                .add(emitter);

        // All three, not just completion: a client that navigates away fires onCompletion, an
        // idle one fires onTimeout, and a dropped connection fires onError. Registering only the
        // first is how an emitter map turns into a memory leak.
        emitter.onCompletion(() -> remove(submissionId, emitter));
        emitter.onTimeout(() -> remove(submissionId, emitter));
        emitter.onError(throwable -> remove(submissionId, emitter));

        return emitter;
    }

    /**
     * Pushes a verdict to everyone watching that submission and closes their streams - there is
     * nothing further to say about a judged submission.
     */
    public void publish(SubmissionResponse submission) {
        List<SseEmitter> emitters = emittersBySubmission.remove(submission.id());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("verdict").data(submission));
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                // A browser closing mid-send is ordinary, not exceptional: the tab was shut
                // between the map lookup and the write. Nothing to recover, nothing to alarm on.
                log.debug("Dropping closed SSE connection for submission {}", submission.id());
                emitter.completeWithError(e);
            }
        }
    }

    /** Visible for tests: how many connections are currently held. */
    public int activeConnections() {
        return emittersBySubmission.values().stream().mapToInt(List::size).sum();
    }

    private void remove(Long submissionId, SseEmitter emitter) {
        emittersBySubmission.computeIfPresent(submissionId, (id, emitters) -> {
            emitters.remove(emitter);
            // Returning null removes the key, so the map does not accumulate an entry per
            // submission ever watched.
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
