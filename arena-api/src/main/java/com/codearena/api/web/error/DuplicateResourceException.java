package com.codearena.api.web.error;

/**
 * Thrown when a request would create a resource that already exists under a unique key.
 * Mapped to 409 by {@link GlobalExceptionHandler}.
 */
public class DuplicateResourceException extends RuntimeException {

    private final String resourceType;
    private final String identifier;

    public DuplicateResourceException(String resourceType, Object identifier) {
        super("%s '%s' already exists".formatted(resourceType, identifier));
        this.resourceType = resourceType;
        this.identifier = String.valueOf(identifier);
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getIdentifier() {
        return identifier;
    }
}
