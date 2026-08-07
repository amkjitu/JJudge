package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UserTagStat", description = "A user's record on one topic")
public record UserTagStatResponse(

        @Schema(example = "dp")
        String tag,

        @Schema(description = "Distinct problems with this tag the user has solved", example = "1")
        int solvedCount,

        @Schema(description = "Distinct problems with this tag the user has attempted", example = "4")
        int attemptCount,

        @Schema(description = "Smoothed solved/(attempts + k) ratio in [0, 1)", example = "0.1429")
        double proficiency
) {
}
