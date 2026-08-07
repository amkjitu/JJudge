package com.codearena.api.web.error;

import java.net.URI;

/**
 * Stable {@code type} URIs for RFC 7807 responses.
 *
 * <p>RFC 7807 treats {@code type} as the machine-readable identity of the error - clients are
 * meant to branch on it rather than on the human-readable {@code title} or on a bare status
 * code. Keeping them in one enum stops them drifting into string literals scattered across
 * handlers.
 */
public enum ErrorType {

    VALIDATION_FAILED("validation-failed", "Request validation failed"),
    RESOURCE_NOT_FOUND("resource-not-found", "Resource not found"),
    RESOURCE_CONFLICT("resource-conflict", "Resource conflict"),
    MALFORMED_REQUEST("malformed-request", "Malformed request"),
    INTERNAL_ERROR("internal-error", "Internal server error");

    private static final String BASE = "https://codearena.dev/errors/";

    private final URI type;
    private final String title;

    ErrorType(String slug, String title) {
        this.type = URI.create(BASE + slug);
        this.title = title;
    }

    public URI type() {
        return type;
    }

    public String title() {
        return title;
    }
}
