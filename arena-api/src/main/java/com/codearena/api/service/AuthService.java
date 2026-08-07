package com.codearena.api.service;

import com.codearena.api.domain.User;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.security.JwtService;
import com.codearena.api.security.RefreshTokenService;
import com.codearena.api.web.dto.LoginRequest;
import com.codearena.api.web.dto.RegisterRequest;
import com.codearena.api.web.dto.TokenPairResponse;
import com.codearena.api.web.error.DuplicateResourceException;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.common.domain.AuthProvider;
import com.codearena.common.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** Every new account starts here; Elo drifts from this once verdicts arrive in Phase 6. */
    private static final int STARTING_RATING = 1200;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager,
                       JwtService jwtService,
                       RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public TokenPairResponse register(RegisterRequest request) {
        // Checked up front for a clean 409; the unique indexes remain the real arbiter, since
        // this check-then-insert is racy under concurrency. DataIntegrityViolationException
        // from a lost race is also mapped to 409.
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username", request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email", request.email());
        }

        User user = userRepository.save(User.builder()
                .username(request.username())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .authProvider(AuthProvider.LOCAL)
                .role(Role.USER)
                .rating(STARTING_RATING)
                .build());

        log.info("Registered local account '{}'", user.getUsername());
        return issueTokens(user);
    }

    /**
     * Delegates the credential check to {@link AuthenticationManager} rather than comparing
     * hashes here. That keeps one code path for password verification, and picks up the
     * constant-time comparison and the "encode a dummy password when the user does not exist"
     * timing defence that {@code DaoAuthenticationProvider} already implements.
     */
    @Transactional
    public TokenPairResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.usernameOrEmail(), request.password()));

        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResourceNotFoundException("User", authentication.getName()));

        return issueTokens(user);
    }

    @Transactional
    public TokenPairResponse refresh(String refreshToken) {
        RefreshTokenService.IssuedRefreshToken rotated = refreshTokenService.rotate(refreshToken);
        User user = rotated.stored().getUser();

        JwtService.IssuedAccessToken access = jwtService.issueAccessToken(user);
        return TokenPairResponse.of(access.value(), access.expiresInSeconds(),
                rotated.value(), rotated.expiresAt(), user.getUsername());
    }

    @Transactional
    public void logout(String refreshToken) {
        // Idempotent by design: logging out twice, or with a token that has already expired, is
        // not an error worth telling the client about.
        refreshTokenService.revoke(refreshToken);
    }

    @Transactional
    public TokenPairResponse issueTokens(User user) {
        JwtService.IssuedAccessToken access = jwtService.issueAccessToken(user);
        RefreshTokenService.IssuedRefreshToken refresh = refreshTokenService.issue(user);

        return TokenPairResponse.of(access.value(), access.expiresInSeconds(),
                refresh.value(), refresh.expiresAt(), user.getUsername());
    }
}
