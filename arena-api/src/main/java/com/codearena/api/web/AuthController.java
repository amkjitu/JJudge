package com.codearena.api.web;

import com.codearena.api.security.RefreshTokenCookies;
import com.codearena.api.service.AuthService;
import com.codearena.api.service.CurrentUserProvider;
import com.codearena.api.service.UserService;
import com.codearena.api.web.dto.LoginRequest;
import com.codearena.api.web.dto.RefreshRequest;
import com.codearena.api.web.dto.RegisterRequest;
import com.codearena.api.web.dto.TokenPairResponse;
import com.codearena.api.web.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login, token refresh and logout")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final CurrentUserProvider currentUserProvider;
    private final boolean secureCookies;

    public AuthController(AuthService authService,
                          UserService userService,
                          CurrentUserProvider currentUserProvider,
                          @Value("${arena.security.secure-cookies:false}") boolean secureCookies) {
        this.authService = authService;
        this.userService = userService;
        this.currentUserProvider = currentUserProvider;
        this.secureCookies = secureCookies;
    }

    @PostMapping("/register")
    @Operation(summary = "Create a local account",
            description = "Returns a token pair, so a client does not have to log in immediately afterwards.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "409", description = "Username or email already taken", content = @Content)
    })
    public ResponseEntity<TokenPairResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange credentials for a token pair")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "401", description = "Bad credentials", content = @Content)
    })
    public TokenPairResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange a refresh token for a new pair",
            description = """
                    Rotates: the presented refresh token is consumed and a new one returned.
                    Presenting a token that has already been rotated is treated as evidence of
                    theft and revokes every session for that account.

                    The token may arrive in the request body or in the `arena_refresh` cookie -
                    the latter is how the OAuth2 browser flow delivers it, since a redirect has
                    no JSON response to carry it in.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Refreshed"),
            @ApiResponse(responseCode = "400", description = "No refresh token supplied", content = @Content),
            @ApiResponse(responseCode = "401", description = "Unknown, expired or already-used token", content = @Content)
    })
    public ResponseEntity<TokenPairResponse> refresh(
            @RequestBody(required = false) RefreshRequest request,
            @CookieValue(name = RefreshTokenCookies.COOKIE_NAME, required = false) String cookieToken) {

        String token = resolveRefreshToken(request, cookieToken);
        TokenPairResponse tokens = authService.refresh(token);

        // The rotated value replaces whatever the browser is holding; without this the cookie
        // would still contain the consumed token and the next refresh would look like reuse.
        ResponseCookie cookie = RefreshTokenCookies.issue(tokens.refreshToken(),
                Duration.between(Instant.now(), tokens.refreshTokenExpiresAt()), secureCookies);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(tokens);
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke a refresh token",
            description = """
                    Idempotent - revoking an unknown or already-revoked token succeeds. The
                    access token is not revoked because it cannot be; it simply expires, which
                    is why its lifetime is short.
                    """)
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) RefreshRequest request,
            @CookieValue(name = RefreshTokenCookies.COOKIE_NAME, required = false) String cookieToken) {

        String token = request != null && request.refreshToken() != null && !request.refreshToken().isBlank()
                ? request.refreshToken()
                : cookieToken;

        if (token != null && !token.isBlank()) {
            authService.logout(token);
        }

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, RefreshTokenCookies.clear(secureCookies).toString())
                .build();
    }

    private static String resolveRefreshToken(RefreshRequest request, String cookieToken) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            return request.refreshToken();
        }
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken;
        }
        throw new IllegalArgumentException(
                "A refresh token is required, either in the request body or the "
                        + RefreshTokenCookies.COOKIE_NAME + " cookie");
    }

    @GetMapping("/me")
    @Operation(summary = "The authenticated user's own profile",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    public UserProfileResponse me() {
        return userService.getProfile(currentUserProvider.currentUsername());
    }
}
