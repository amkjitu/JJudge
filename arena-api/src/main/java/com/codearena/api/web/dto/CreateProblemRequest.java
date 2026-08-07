package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Note the absence of a {@code difficulty} field: it is derived from {@code rating} by
 * {@link com.codearena.common.domain.Difficulty#fromRating(int)}. Accepting both would let a
 * caller create a problem labelled EASY at rating 2200.
 */
@Schema(name = "CreateProblemRequest")
public record CreateProblemRequest(

        @NotBlank
        @Size(max = 200)
        @Schema(example = "Dijkstra on a Weighted Grid")
        String title,

        @NotBlank
        @Size(max = 200)
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                message = "must be lower-case words separated by single hyphens")
        @Schema(example = "dijkstra-on-a-weighted-grid")
        String slug,

        @NotNull
        @Min(0)
        @Max(4000)
        @Schema(example = "1500", description = "Difficulty bucket is derived from this")
        Integer rating,

        @Min(100)
        @Max(20000)
        @Schema(example = "2000", defaultValue = "1000")
        Integer timeLimitMs,

        @Min(16)
        @Max(1024)
        @Schema(example = "256", defaultValue = "256")
        Integer memoryLimitMb,

        @NotEmpty
        @Schema(example = "[\"shortest-path\", \"heap\", \"graph\"]",
                description = "Tag names; every one must already exist")
        Set<@NotBlank String> tags
) {
}
