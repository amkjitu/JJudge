package com.codearena.api.ai;

import com.codearena.api.ai.dto.ComplexityView;
import com.codearena.api.ai.dto.HintView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Calls arena-ai over HTTP.
 *
 * <h2>Why HTTP and not Kafka</h2>
 *
 * <p>The judging pipeline is asynchronous because judging takes seconds and nobody should hold a
 * connection open for it. A hint is the opposite: somebody is looking at the screen waiting for
 * it, and there is no answer to give them until it arrives. Routing a request/response
 * interaction through a broker would add a correlation id, a reply topic and a timeout to
 * reimplement exactly what HTTP already does.
 *
 * <h2>Every failure returns empty</h2>
 *
 * <p>A hint is an extra. If arena-ai is down, slow, or returns something unparseable, the
 * problem page must still render - so this returns {@link Optional#empty()} and the caller shows
 * the page without a hint. The alternative, propagating the exception, would let an optional
 * service take down a page that does not need it.
 */
public class AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private final RestClient restClient;

    public AiClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public Optional<HintView> hint(String problemTitle, Set<String> tags, Integer rating,
                                   int level, String attemptedSourceCode) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("problemTitle", problemTitle);
        body.put("tags", tags == null ? Set.of() : tags);
        body.put("rating", rating);
        body.put("level", level);
        if (attemptedSourceCode != null && !attemptedSourceCode.isBlank()) {
            body.put("attemptedSourceCode", attemptedSourceCode);
        }

        return post("/api/v1/ai/hints", body, HintView.class, "hint for '" + problemTitle + "'");
    }

    public Optional<ComplexityView> complexity(String language, String sourceCode) {
        return post("/api/v1/ai/complexity",
                Map.of("language", language, "sourceCode", sourceCode),
                ComplexityView.class,
                "complexity analysis");
    }

    private <T> Optional<T> post(String path, Object body, Class<T> type, String what) {
        try {
            return Optional.ofNullable(restClient.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(type));
        } catch (RestClientException e) {
            // Covers the connection being refused, the timeout, a 4xx/5xx and a body that will
            // not deserialise. They all mean the same thing to the caller: no answer this time.
            log.warn("arena-ai could not provide a {}: {}", what, e.getMessage());
            return Optional.empty();
        }
    }
}
