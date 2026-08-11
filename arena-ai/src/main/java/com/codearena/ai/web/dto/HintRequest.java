package com.codearena.ai.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

@Schema(name = "HintRequest", description = "What the hint should be about")
public record HintRequest(

        @NotBlank
        @Size(max = 200)
        @Schema(example = "Maximum Subarray Sum")
        String problemTitle,

        @Schema(example = "[\"arrays\", \"dp\"]")
        Set<String> tags,

        @Schema(example = "1100")
        Integer rating,

        /**
         * 1 is the gentlest nudge, 3 the most specific. Bounded rather than open-ended because
         * hints past level 3 stop being hints.
         */
        @Min(1)
        @Max(3)
        @Schema(example = "1", defaultValue = "1")
        Integer level,

        /**
         * Optional. When present the hint is aimed at what the attempt appears to be missing,
         * which is the difference between a generic nudge and a useful one.
         */
        @Size(max = 65536)
        String attemptedSourceCode
) {

    /** Never null, so callers do not each repeat the check. */
    public Set<String> safeTags() {
        return tags == null ? Set.of() : tags;
    }
}
