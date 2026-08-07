package com.codearena.api.web.dto;

import com.codearena.common.domain.Difficulty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/**
 * Listing projection. Deliberately omits the statement body and limits - the problem list
 * renders hundreds of these and none of that is displayed.
 */
@Schema(name = "ProblemSummary", description = "A problem as it appears in listings")
public record ProblemSummaryResponse(

        @Schema(example = "42")
        Long id,

        @Schema(example = "Dijkstra on a Weighted Grid")
        String title,

        @Schema(description = "URL-safe identifier used in public routes", example = "dijkstra-on-a-weighted-grid")
        String slug,

        @Schema(example = "MEDIUM")
        Difficulty difficulty,

        @Schema(description = "Numeric difficulty, the value the recommender scores against", example = "1500")
        Integer rating,

        @Schema(example = "[\"shortest-path\", \"heap\", \"graph\"]")
        Set<String> tags
) {
}
