package com.codearena.ai.complexity;

import com.codearena.ai.AnswerSource;
import com.codearena.ai.config.AiProperties;
import com.codearena.ai.config.ModelAvailability;
import com.codearena.ai.web.dto.ComplexityResponse;
import com.codearena.common.domain.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Explains what a solution costs to run.
 *
 * <p>Asks a model when one is available and falls back to {@link StaticComplexityAnalyser}
 * otherwise - or when the model is too slow, or answers with something unusable. Every response
 * says which happened.
 */
@Service
public class ComplexityService {

    private static final Logger log = LoggerFactory.getLogger(ComplexityService.class);

    private static final String SYSTEM_PROMPT = """
            You analyse the asymptotic complexity of competitive-programming solutions.

            Answer only about the code you are given. State time and space complexity in big-O
            notation using n for the input size. Give a short explanation - two or three
            sentences - naming the specific construct that drives the bound.

            If the code is incomplete or its bound genuinely depends on something not visible,
            say so in the explanation rather than guessing a precise bound.
            """;

    private static final String USER_PROMPT = """
            Language: {language}

            ```
            {code}
            ```
            """;

    private final ObjectProvider<ChatClient> chatClient;
    private final StaticComplexityAnalyser analyser;
    private final AiProperties properties;
    private final ModelAvailability availability;

    public ComplexityService(ObjectProvider<ChatClient> chatClient,
                             StaticComplexityAnalyser analyser,
                             AiProperties properties,
                             ModelAvailability availability) {
        this.chatClient = chatClient;
        this.analyser = analyser;
        this.properties = properties;
        this.availability = availability;
    }

    public ComplexityResponse analyse(Language language, String sourceCode) {
        ChatClient client = chatClient.getIfAvailable();
        if (client != null && availability.shouldTry()) {
            ComplexityResponse answer = askModel(client, language, sourceCode);
            if (answer != null) {
                availability.recordSuccess();
                return answer;
            }
            availability.recordFailure();
        }
        return heuristic(sourceCode);
    }

    /**
     * @return the model's answer, or {@code null} if it could not produce a usable one. Null
     *         rather than an exception because there is nothing exceptional here - the caller
     *         has a perfectly good second option and every failure mode leads to the same place.
     */
    private ComplexityResponse askModel(ChatClient client, Language language, String sourceCode) {
        try {
            ModelAnswer answer = callWithTimeout(client, language, sourceCode, properties.timeout());

            // A model that returns an empty or malformed structure is a failed call, not an
            // answer. Passing "" through as a complexity would be worse than falling back.
            if (answer == null || answer.timeComplexity() == null || answer.timeComplexity().isBlank()) {
                log.warn("Model returned no usable complexity; falling back to static analysis");
                return null;
            }

            return new ComplexityResponse(answer.timeComplexity(),
                    blankToNull(answer.spaceComplexity()),
                    answer.explanation(),
                    List.of(),
                    null,
                    AnswerSource.MODEL);

        } catch (TimeoutException e) {
            log.warn("Model did not answer within {}; falling back to static analysis",
                    properties.timeout());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Model call failed ({}); falling back to static analysis", e.getMessage());
            return null;
        }
    }

    /**
     * Bounds a synchronous call.
     *
     * <p>{@code ChatClient} blocks, and a local model under memory pressure can block for a very
     * long time. Without this the request thread is held hostage by a dependency the response
     * does not actually need - the fallback is right there. The abandoned call is left to finish
     * on its own thread rather than interrupted, since cancelling mid-stream buys nothing.
     */
    private ModelAnswer callWithTimeout(ChatClient client, Language language, String sourceCode,
                                        Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {

        CompletableFuture<ModelAnswer> future = CompletableFuture.supplyAsync(() ->
                client.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(u -> u.text(USER_PROMPT)
                                .param("language", language.name())
                                .param("code", sourceCode))
                        .call()
                        .entity(ModelAnswer.class));

        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private ComplexityResponse heuristic(String sourceCode) {
        ComplexityEstimate estimate = analyser.analyse(sourceCode);
        return new ComplexityResponse(estimate.timeComplexity(),
                estimate.spaceComplexity(),
                String.join(" ", estimate.reasons()),
                estimate.reasons(),
                estimate.caveat(),
                AnswerSource.HEURISTIC);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** The structure the model is asked to fill in. Spring AI maps the reply onto it. */
    record ModelAnswer(String timeComplexity, String spaceComplexity, String explanation) {
    }
}
