package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "TagDifficultyRow", description = "Aggregate difficulty statistics for one topic")
public record TagDifficultyRow(

        @Schema(example = "dp")
        String tag,

        @Schema(description = "Problems carrying this tag", example = "9")
        int problemCount,

        @Schema(description = "Submissions against problems with this tag", example = "23")
        long totalSubmissions,

        @Schema(example = "8")
        long acceptedSubmissions,

        @Schema(description = "Users with at least one accepted solution on this topic", example = "3")
        long distinctSolvers,

        @Schema(description = "acceptedSubmissions / totalSubmissions, or null when untouched",
                nullable = true, example = "0.3478")
        Double acceptanceRate,

        @Schema(description = "Mean attempts a user needed on problems they eventually solved",
                nullable = true, example = "1.6")
        Double avgAttemptsToSolve,

        @Schema(description = "Mean runtime of accepted submissions, milliseconds",
                nullable = true, example = "212.5")
        Double avgAcceptedRuntimeMs
) {
}
