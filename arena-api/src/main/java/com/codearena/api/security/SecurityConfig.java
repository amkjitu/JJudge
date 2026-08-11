package com.codearena.api.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Two filter chains, because the API and the browser login flow have incompatible needs.
 *
 * <ol>
 *   <li><b>{@code /api/**}</b> - stateless bearer-token resource server. No session, no CSRF
 *       token (there is no cookie to ride on), no redirect to a login page: an unauthenticated
 *       call gets a 401 body, not a 302 to HTML.</li>
 *   <li><b>everything else</b> - the OAuth2 redirect dance, which genuinely needs a session to
 *       hold the {@code state} parameter between the authorization request and the callback.
 *       </li>
 * </ol>
 *
 * <p>Trying to serve both from one chain means either the API grows a session or the OAuth2
 * flow loses its CSRF protection.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, SecurityHardeningProperties.class})
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/problems", "/api/v1/problems/**",
            "/api/v1/tags", "/api/v1/tags/**",
            "/api/v1/users/*", "/api/v1/users/*/submissions",
            "/api/v1/reports/**",
            "/api/v1/recommendations/users/*"
    };

    /**
     * The API chain. Ordered first so its {@code securityMatcher} wins for {@code /api/**}.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                              JwtDecoder jwtDecoder,
                                              ProblemDetailAuthenticationHandlers handlers) throws Exception {
        http
                .securityMatcher("/api/**")
                // Safe to disable only because this chain is stateless and token-based: with no
                // cookie carrying authentication, a cross-site form post has nothing to forge.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // Browsing the catalogue needs no account. Submitting does.
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers));

        return http.build();
    }

    /**
     * The browser chain: the Thymeleaf UI, Swagger, actuator, and the OAuth2 login redirect.
     *
     * <p>Session-based rather than token-based, deliberately. A server-rendered page has
     * nowhere to keep a bearer token that JavaScript cannot also read, so putting a JWT in
     * {@code localStorage} for the UI would trade a well-understood session cookie for an
     * XSS-readable credential. The session cookie is {@code HttpOnly}, and CSRF protection
     * stays on precisely because authentication now rides on a cookie.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http,
                                              ProblemDetailAuthenticationHandlers handlers,
                                              ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                              ObjectProvider<OAuth2LoginSuccessHandler> oauth2SuccessHandler)
            throws Exception {
        RequestMatcher machineReadable = new AntPathRequestMatcher("/actuator/**");

        http
                .authorizeHttpRequests(auth -> auth
                        // Static assets and the WebJar-served front-end libraries.
                        .requestMatchers("/css/**", "/js/**", "/webjars/**", "/favicon.ico").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Browsing is public; the catalogue is the shop window.
                        .requestMatchers(HttpMethod.GET,
                                "/", "/error", "/error/**", "/login", "/register",
                                "/problems", "/problems/*", "/users/*", "/leaderboard").permitAll()
                        .requestMatchers("/login", "/register", "/oauth2/**", "/login/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())

                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("usernameOrEmail")
                        // No alwaysUse: Spring replays the originally requested page, so a
                        // deep link into a protected page survives the login detour.
                        .defaultSuccessUrl("/")
                        .failureUrl("/login?error")
                        .permitAll())

                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/?loggedOut")
                        .deleteCookies(RefreshTokenCookies.COOKIE_NAME)
                        .invalidateHttpSession(true)
                        .clearAuthentication(true))

                .sessionManagement(session -> session
                        // Fresh session id on login, so a session id an attacker planted before
                        // authentication is not the one that ends up authenticated.
                        .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::newSession)
                        .maximumSessions(5))

                .exceptionHandling(ex -> ex
                        // Actuator is consumed by machines: answer 401 with a problem document
                        // rather than a 302 to an HTML login form no scraper can follow.
                        .defaultAuthenticationEntryPointFor(handlers, machineReadable)
                        .defaultAccessDeniedHandlerFor(handlers, machineReadable)
                        // Everything else is a browser, so send it to the login page. Spring's
                        // formLogin entry point is applied automatically for the remainder.
                        .accessDeniedPage("/error/403"));

        // Google login is optional configuration. Without a client registration there is no
        // ClientRegistrationRepository bean, and calling oauth2Login() would fail at startup -
        // so the whole application would refuse to boot just because a demo deployment has no
        // Google credentials.
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        if (registrations != null) {
            OAuth2LoginSuccessHandler successHandler = oauth2SuccessHandler.getObject();
            http.oauth2Login(oauth2 -> oauth2
                    .loginPage("/login")
                    .successHandler(successHandler));
        }

        return http.build();
    }

    /**
     * Maps the {@code roles} claim onto authorities. The default converter reads {@code scope}
     * or {@code scp} and prefixes {@code SCOPE_}, which would make {@code hasRole('ADMIN')}
     * silently never match.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(JwtService.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 10 matches the seeded hashes; the default of 10 is also Spring's.
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Checks exp/nbf *and* that the issuer is ours, so a token minted by another service
        // sharing the key would still be rejected.
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static SecretKeySpec secretKey(JwtProperties properties) {
        return new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
