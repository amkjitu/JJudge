package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ProgressPoint", description = "One point on the cumulative solved-problems curve")
public record ProgressPointResponse(

        @Schema(description = "Month, as YYYY-MM", example = "2026-03")
        String month,

        @Schema(description = "Distinct problems solved by the end of this month", example = "14")
        long cumulativeSolved
) {
}
