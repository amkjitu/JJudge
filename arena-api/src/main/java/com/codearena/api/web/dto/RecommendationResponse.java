package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "Recommendation", description = "A suggested problem, with the reasoning behind it")
public record RecommendationResponse(

        ProblemSummaryResponse problem,

        @Schema(description = "Combined score; only meaningful relative to the other entries",
                example = "0.7412")
        double score,

        @Schema(description = "A short human-readable justification",
                example = "targets a weak topic (dp) at a slight stretch")
        String reason,

        WhyResponse why
) {

    /**
     * The individual scoring terms, all in [0, 1] before weighting.
     *
     * <p>Exposed rather than hidden because a recommender that cannot explain itself is
     * indistinguishable from a shuffle - and because it is what makes the panel debuggable
     * from the outside when a suggestion looks wrong.
     */
    @Schema(name = "RecommendationWhy")
    public record WhyResponse(
            @Schema(description = "How unfamiliar the problem's topics are, averaged", example = "0.86")
            double tagWeakness,

            @Schema(description = "How well the rating matches a mild stretch", example = "0.94")
            double ratingFit,

            @Schema(description = "How recently the problem was added", example = "0.71")
            double recency,

            @Schema(description = "How often this user has already failed it", example = "0.0")
            double repetitionPenalty
    ) {
    }
}
