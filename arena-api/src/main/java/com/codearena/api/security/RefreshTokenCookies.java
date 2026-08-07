package com.codearena.api.security;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * Cookie handling for the refresh token.
 *
 * <p>The OAuth2 login flow ends in a browser redirect, so it cannot hand tokens back in a JSON
 * response. The obvious alternatives - query parameter or URL fragment - both write a
 * long-lived credential into browser history, and a query parameter additionally leaks through
 * {@code Referer} headers and server logs. An {@code HttpOnly} cookie keeps the refresh token
 * out of JavaScript's reach and out of the URL; the client then exchanges it for an access
 * token through {@code POST /api/v1/auth/refresh}, which accepts the cookie as well as a body.
 *
 * <p>The access token is never put in a cookie: it travels in the {@code Authorization} header,
 * which is what makes the API chain immune to CSRF.
 */
public final class RefreshTokenCookies {

    public static final String COOKIE_NAME = "arena_refresh";

    private RefreshTokenCookies() {
    }

    public static ResponseCookie issue(String token, Duration maxAge, boolean secure) {
        return baseBuilder(token, secure)
                .maxAge(maxAge)
                .build();
    }

    /** A zero-age cookie with an empty value, which is how a cookie is deleted. */
    public static ResponseCookie clear(boolean secure) {
        return baseBuilder("", secure)
                .maxAge(Duration.ZERO)
                .build();
    }

    private static ResponseCookie.ResponseCookieBuilder baseBuilder(String value, boolean secure) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                // Off in local HTTP development, on everywhere else - a Secure cookie is simply
                // never sent over plain HTTP, which would silently break the local stack.
                .secure(secure)
                // Lax rather than Strict: the OAuth2 callback is a cross-site top-level
                // navigation, and Strict would drop the cookie on exactly that redirect.
                .sameSite("Lax")
                // Scoped to the refresh endpoint so it is not attached to every API call.
                .path("/api/v1/auth");
    }
}
