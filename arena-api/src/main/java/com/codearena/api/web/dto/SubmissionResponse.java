package com.codearena.api.web.dto;

import com.codearena.common.domain.Language;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "Submission")
public record SubmissionResponse(

        Long id,

        Long problemId,

        String problemSlug,

        String problemTitle,

        String username,

        Language language,

        @Schema(example = "DONE")
        SubmissionStatus status,

        @Schema(description = "Null until judging completes", nullable = true, example = "AC")
        Verdict verdict,

        @Schema(nullable = true, example = "145")
        Integer runtimeMs,

        Instant submittedAt
) {
}
