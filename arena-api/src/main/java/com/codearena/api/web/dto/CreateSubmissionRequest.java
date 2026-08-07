package com.codearena.api.web.dto;

import com.codearena.common.domain.Language;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateSubmissionRequest")
public record CreateSubmissionRequest(

        @NotBlank
        @Schema(example = "maximum-subarray-sum")
        String problemSlug,

        @NotNull
        @Schema(example = "JAVA")
        Language language,

        /**
         * Capped at 64 KiB - comfortably more than any legitimate solution, and small enough
         * that the endpoint cannot be used to push megabytes through the judge queue.
         */
        @NotBlank
        @Size(max = 65536, message = "source code must not exceed 64 KiB")
        @Schema(example = "public class Main { public static void main(String[] a) {} }")
        String sourceCode
) {
}
