package com.codearena.api.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Pre-authentication stand-in: reads the caller from an {@code X-Arena-User} header, falling
 * back to a configured demo account so the API is usable before Phase 3 exists.
 *
 * <p><strong>This is not authentication.</strong> It trusts a client-supplied header and is
 * trivially spoofable. It exists only so the submission endpoints are exercisable in Phase 2,
 * and Phase 3 deletes this class outright in favour of a {@link CurrentUserProvider} backed by
 * {@code SecurityContextHolder}. Nothing else has to change when it does - that is the point
 * of putting the interface in front of it.
 */
@Component
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    public static final String USER_HEADER = "X-Arena-User";

    private final HttpServletRequest request;
    private final String defaultUsername;

    public HeaderCurrentUserProvider(HttpServletRequest request,
                                     @Value("${arena.demo-user:bob}") String defaultUsername) {
        this.request = request;
        this.defaultUsername = defaultUsername;
    }

    @Override
    public String currentUsername() {
        String header = request.getHeader(USER_HEADER);
        return header == null || header.isBlank() ? defaultUsername : header.trim();
    }
}
