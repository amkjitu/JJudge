package com.codearena.common.domain;

/**
 * How an account authenticates.
 *
 * <p>{@code LOCAL} accounts carry a BCrypt password hash; federated ones never do, and the
 * database enforces that correspondence rather than trusting the application to remember it.
 */
public enum AuthProvider {
    LOCAL,
    GOOGLE
}
