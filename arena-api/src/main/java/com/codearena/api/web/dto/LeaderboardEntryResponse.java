package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "LeaderboardEntry")
public record LeaderboardEntryResponse(

        @Schema(description = "1-based position", example = "3")
        int rank,

        @Schema(example = "carol")
        String username,

        @Schema(example = "1750")
        int rating,

        @Schema(description = "Distinct problems solved", example = "25")
        long solvedCount
) {
}
