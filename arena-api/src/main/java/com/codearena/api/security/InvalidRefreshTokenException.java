package com.codearena.api.security;

/**
 * Raised when a refresh token cannot be redeemed. Mapped to 401 by the global handler.
 *
 * <p>The message deliberately does not distinguish "no such token" from "expired" in a way a
 * caller could use to probe which tokens exist - all three cases mean the same thing to a
 * legitimate client: log in again.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
