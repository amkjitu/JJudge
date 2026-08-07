package com.codearena.api.service;

/**
 * Resolves who is making the current request.
 *
 * <p>This exists so that no controller or service ever takes a username from a request body.
 * Phase 3 replaces the implementation with one backed by Spring Security's
 * {@code SecurityContextHolder}; because callers depend on this interface rather than on how
 * identity is established, that swap touches exactly one class.
 */
public interface CurrentUserProvider {

    /**
     * @return the username of the caller
     */
    String currentUsername();
}
