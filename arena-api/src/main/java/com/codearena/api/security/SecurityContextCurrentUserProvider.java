package com.codearena.api.security;

import com.codearena.api.service.CurrentUserProvider;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The real {@link CurrentUserProvider}, replacing the {@code X-Arena-User} header stand-in that
 * stood in for it through Phase 2.
 *
 * <p>Reads {@link Authentication#getName()} rather than casting to a concrete principal type,
 * because the same code has to serve three sources of identity: a bearer JWT (subject claim),
 * an OAuth2 login, and {@code @WithMockUser} in tests. All three populate the name.
 */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    @Override
    public String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            // Reaching here means an endpoint that needs an identity was left off the
            // authenticated matcher list - a configuration bug, not a client error. Throwing an
            // AuthenticationException yields a 401 rather than a confusing NPE further down.
            throw new InsufficientAuthenticationException("No authenticated user in the security context");
        }

        return authentication.getName();
    }
}
