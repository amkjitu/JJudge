package com.codearena.common.domain;

/**
 * Application roles. Persisted without the {@code ROLE_} prefix; Spring Security's
 * {@code hasRole()} adds it back at authorization time.
 */
public enum Role {
    USER,
    ADMIN
}
