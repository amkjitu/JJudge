package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "RegisterRequest")
public record RegisterRequest(

        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
                message = "may contain only letters, digits, underscores and hyphens")
        @Schema(example = "newcomer")
        String username,

        @NotBlank
        @Email
        @Size(max = 255)
        @Schema(example = "newcomer@example.com")
        String email,

        /**
         * Length is the constraint that actually matters; composition rules mostly push people
         * towards predictable substitutions. The floor of 10 is above the NIST minimum of 8.
         */
        @NotBlank
        @Size(min = 10, max = 100, message = "must be between 10 and 100 characters")
        @Schema(example = "correct-horse-battery", minLength = 10)
        String password
) {
}
