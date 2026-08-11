package com.codearena.ai.hint;

import com.codearena.ai.AnswerSource;
import com.codearena.ai.config.AiProperties;
import com.codearena.ai.web.dto.HintRequest;
import com.codearena.ai.web.dto.HintResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Produces a nudge, not a solution.
 *
 * <h2>The level is a real constraint, not decoration</h2>
 *
 * <p>Hints are requested by level: the first is a question about how to think, and each
 * subsequent one is more specific. Someone who wants the answer can read the editorial - which
 * the platform gives them, behind a spoiler. The point of a hint is to be the smallest push that
 * gets a person unstuck, because a solved-with-a-nudge problem teaches and a read-solution does
 * not.
 *
 * <p>That is why the system prompt forbids code and names the ceiling explicitly. Models are
 * obliging by default and will write the whole solution given the chance, which would quietly
 * turn a practice platform into an answer key.
 */
@Service
public class HintService {

    private static final Logger log = LoggerFactory.getLogger(HintService.class);

    /** Beyond this the hints would be the solution. */
    public static final int MAX_LEVEL = 3;

    private static final String SYSTEM_PROMPT = """
            You give hints to someone practising competitive programming. You are helping them
            solve the problem themselves.

            Rules, in order of importance:
            1. Never write code, pseudocode, or a step-by-step algorithm.
            2. Never name a complete solution. Naming a general technique is allowed at level 3.
            3. One or two sentences. A hint that needs a paragraph is a lecture.
            4. Prefer a question over a statement. "What stays true as the window grows?" beats
               "Use a sliding window."

            Level 1 is a question about how to approach the problem at all. Level 2 points at the
            structure that makes it tractable. Level 3 may name the technique, but never how to
            implement it.

            If the user's attempt is included, aim the hint at what they appear to be missing -
            but do not review, correct or debug their code.
            """;

    private static final String USER_PROMPT = """
            Problem: {title}
            Difficulty rating: {rating}
            Topics: {tags}
            Hint level requested: {level}
            {attempt}
            """;

    private final ObjectProvider<ChatClient> chatClient;
    private final TagHintLibrary library;
    private final AiProperties properties;

    public HintService(ObjectProvider<ChatClient> chatClient,
                       TagHintLibrary library,
                       AiProperties properties) {
        this.chatClient = chatClient;
        this.library = library;
        this.properties = properties;
    }

    public HintResponse hint(HintRequest request) {
        int requested = request.level() == null ? 1 : request.level();
        int level = Math.max(1, Math.min(requested, MAX_LEVEL));

        ChatClient client = chatClient.getIfAvailable();
        if (client != null) {
            String hint = askModel(client, request, level);
            if (hint != null) {
                return new HintResponse(hint, level, MAX_LEVEL, AnswerSource.MODEL);
            }
        }

        return new HintResponse(fromLibrary(request, level), level, MAX_LEVEL,
                AnswerSource.HEURISTIC);
    }

    private String askModel(ChatClient client, HintRequest request, int level) {
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                    client.prompt()
                            .system(SYSTEM_PROMPT)
                            .user(u -> u.text(USER_PROMPT)
                                    .param("title", request.problemTitle())
                                    .param("rating", String.valueOf(request.rating()))
                                    .param("tags", String.join(", ", request.safeTags()))
                                    .param("level", String.valueOf(level))
                                    .param("attempt", attemptSection(request)))
                            .call()
                            .content());

            String hint = future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            return hint == null || hint.isBlank() ? null : hint.strip();

        } catch (TimeoutException e) {
            log.warn("Model did not answer within {}; using the hint library", properties.timeout());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Model call failed ({}); using the hint library", e.getMessage());
            return null;
        }
    }

    /**
     * The attempt is truncated rather than rejected when it is long. A hint request is not the
     * place to fail someone for pasting a big file, and the opening of a solution is where the
     * approach is visible anyway.
     */
    private String attemptSection(HintRequest request) {
        if (request.attemptedSourceCode() == null || request.attemptedSourceCode().isBlank()) {
            return "";
        }
        String code = request.attemptedSourceCode();
        int limit = properties.maxSourceChars();
        if (code.length() > limit) {
            code = code.substring(0, limit) + "\n... (truncated)";
        }
        return "\nTheir attempt so far:\n```\n" + code + "\n```";
    }

    private String fromLibrary(HintRequest request, int level) {
        List<String> hints = library.forTags(request.safeTags());
        // Levels are 1-based and a technique may have fewer than MAX_LEVEL hints; asking for a
        // level past the end gets the most specific one rather than an error.
        return hints.get(Math.min(level - 1, hints.size() - 1));
    }
}
