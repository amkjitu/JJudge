package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "TokenPair", description = "A short-lived access token and the refresh token that renews it")
public record TokenPairResponse(

        @Schema(description = "Signed JWT. Send as `Authorization: Bearer <token>`.")
        String accessToken,

        @Schema(example = "Bearer")
        String tokenType,

        @Schema(description = "Access token lifetime in seconds", example = "900")
        long expiresIn,

        @Schema(description = """
                Opaque, single-use token. Every refresh rotates it, so the value returned here
                replaces the one that was sent. Presenting a rotated token revokes every
                session for the account.
                """)
        String refreshToken,

        @Schema(description = "When the refresh token stops working")
        Instant refreshTokenExpiresAt,

        @Schema(example = "bob")
        String username
) {

    public static TokenPairResponse of(String accessToken,
                                       long expiresIn,
                                       String refreshToken,
                                       Instant refreshTokenExpiresAt,
                                       String username) {
        return new TokenPairResponse(accessToken, "Bearer", expiresIn, refreshToken,
                refreshTokenExpiresAt, username);
    }
}
