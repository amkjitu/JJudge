package com.codearena.ai.web.dto;

import com.codearena.ai.AnswerSource;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "HintResponse", description = "A nudge towards the solution")
public record HintResponse(

        @Schema(example = "What subproblem would let you extend a solution by one element?")
        String hint,

        @Schema(example = "1")
        int level,

        @Schema(description = "The highest level available; past this a hint is the solution",
                example = "3")
        int maxLevel,

        @Schema(description = "Whether a model answered or this came from the built-in library")
        AnswerSource source
) {
}
