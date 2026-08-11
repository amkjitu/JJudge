package com.codearena.ai.web.dto;

import com.codearena.common.domain.Language;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "ComplexityRequest", description = "Code to analyse")
public record ComplexityRequest(

        @NotNull
        Language language,

        @NotBlank
        @Size(max = 65536, message = "source code must not exceed 64 KiB")
        String sourceCode
) {
}
