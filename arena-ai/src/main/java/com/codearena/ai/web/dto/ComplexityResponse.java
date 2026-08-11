package com.codearena.ai.web.dto;

import com.codearena.ai.AnswerSource;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "ComplexityResponse", description = "Estimated cost of running the solution")
public record ComplexityResponse(

        @Schema(example = "O(n log n)")
        String timeComplexity,

        @Schema(example = "O(n)", nullable = true)
        String spaceComplexity,

        @Schema(description = "Prose explanation of what drives the bound")
        String explanation,

        /**
         * The individual observations behind a heuristic estimate. Empty for a model answer,
         * whose reasoning is prose rather than a list.
         */
        @Schema(description = "Observations behind a heuristic estimate")
        List<String> reasons,

        /**
         * How this particular estimate could be wrong. Only populated for heuristic answers,
         * where the failure modes are known and worth stating up front.
         */
        @Schema(description = "The most relevant limitation of a heuristic estimate",
                nullable = true)
        String caveat,

        @Schema(description = "Whether a model answered or this came from static analysis")
        AnswerSource source
) {

    public ComplexityResponse {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
