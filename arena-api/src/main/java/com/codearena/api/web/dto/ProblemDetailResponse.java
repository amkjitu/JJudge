package com.codearena.api.web.dto;

import com.codearena.common.domain.Difficulty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;

@Schema(name = "ProblemDetail", description = "Full problem view")
public record ProblemDetailResponse(

        Long id,

        String title,

        String slug,

        Difficulty difficulty,

        Integer rating,

        @Schema(example = "2000")
        Integer timeLimitMs,

        @Schema(example = "256")
        Integer memoryLimitMb,

        Set<String> tags,

        Instant createdAt,

        /**
         * Markdown statement, read from MongoDB. Null when a problem has no statement document
         * yet, and omitted from the payload rather than serialised as an explicit null.
         */
        @Schema(description = "Markdown problem statement", nullable = true)
        String statementMarkdown
) {

    /**
     * A copy carrying the statement.
     *
     * <p>The mapper builds this record from the JPA entity alone, which has no idea MongoDB
     * exists; the service adds the prose afterwards. Keeping the record immutable and copying
     * is what lets both halves stay ignorant of each other.
     */
    public ProblemDetailResponse withStatement(String statementMarkdown) {
        return new ProblemDetailResponse(id, title, slug, difficulty, rating, timeLimitMs,
                memoryLimitMb, tags, createdAt, statementMarkdown);
    }
}
