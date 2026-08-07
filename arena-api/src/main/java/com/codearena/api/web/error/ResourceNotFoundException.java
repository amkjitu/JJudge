package com.codearena.api.web.error;

/**
 * Thrown when a resource addressed by the request does not exist. Mapped to 404 by
 * {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String resourceType;
    private final String identifier;

    public ResourceNotFoundException(String resourceType, Object identifier) {
        super("%s '%s' not found".formatted(resourceType, identifier));
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
