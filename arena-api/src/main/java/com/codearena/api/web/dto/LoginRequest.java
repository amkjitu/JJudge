package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(name = "LoginRequest")
public record LoginRequest(

        @NotBlank
        @Schema(description = "Username or email address", example = "bob")
        String usernameOrEmail,

        @NotBlank
        @Schema(example = "Password123!")
        String password
) {
}
