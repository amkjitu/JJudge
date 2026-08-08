package com.codearena.api.ui;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Small helper so controllers stop repeating the anonymous-token check.
 *
 * <p>Spring injects an {@link AnonymousAuthenticationToken} rather than null on a permitAll
 * route, and that token reports {@code isAuthenticated() == true}. Checking only for null - or
 * only for {@code isAuthenticated()} - is the usual way this goes wrong, and it fails open.
 */
final class UiSecurity {

    private UiSecurity() {
    }

    static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
