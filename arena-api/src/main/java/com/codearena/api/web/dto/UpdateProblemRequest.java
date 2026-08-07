package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * The slug is intentionally not updatable: it is a public URL, and changing it would break
 * every existing link to the problem.
 */
@Schema(name = "UpdateProblemRequest")
public record UpdateProblemRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @NotNull
        @Min(0)
        @Max(4000)
        Integer rating,

        @Min(100)
        @Max(20000)
        Integer timeLimitMs,

        @Min(16)
        @Max(1024)
        Integer memoryLimitMb,

        @NotEmpty
        Set<@NotBlank String> tags
) {
}
