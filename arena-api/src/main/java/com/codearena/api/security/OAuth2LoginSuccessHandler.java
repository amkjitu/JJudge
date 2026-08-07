package com.codearena.api.security;

import com.codearena.api.domain.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Completes a Google login by provisioning the account and planting a refresh-token cookie.
 *
 * <p>The browser arrives here through a redirect, so there is no JSON response to put tokens
 * in. The refresh token goes into an {@code HttpOnly} cookie (see {@link RefreshTokenCookies}
 * for why not the URL) and the browser is sent on to the configured landing page, which
 * exchanges it for an access token via {@code POST /api/v1/auth/refresh}.
 */
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final OAuth2UserProvisioningService provisioningService;
    private final RefreshTokenService refreshTokenService;
    private final String successRedirect;
    private final boolean secureCookies;

    public OAuth2LoginSuccessHandler(OAuth2UserProvisioningService provisioningService,
                                     RefreshTokenService refreshTokenService,
                                     @Value("${arena.oauth2.success-redirect:/}") String successRedirect,
                                     @Value("${arena.security.secure-cookies:false}") boolean secureCookies) {
        this.provisioningService = provisioningService;
        this.refreshTokenService = refreshTokenService;
        this.successRedirect = successRedirect;
        this.secureCookies = secureCookies;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();

        // 'sub' is the stable subject id; email and name are display data only.
        String providerId = principal.getAttribute("sub");
        String email = principal.getAttribute("email");
        String name = principal.getAttribute("name");

        User user = provisioningService.provision(providerId, email, name);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(user);

        response.addHeader(HttpHeaders.SET_COOKIE,
                RefreshTokenCookies.issue(refresh.value(),
                        java.time.Duration.between(refresh.stored().getIssuedAt(), refresh.expiresAt()),
                        secureCookies).toString());

        log.info("Google login completed for '{}'", user.getUsername());
        getRedirectStrategy().sendRedirect(request, response, successRedirect);
    }
}
