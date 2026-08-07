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
         * Markdown statement. Sourced from MongoDB in Phase 7; null until then, and omitted
         * from the payload rather than serialised as an explicit null.
         */
        @Schema(description = "Markdown problem statement (available from Phase 7)", nullable = true)
        String statementMarkdown
) {
}
