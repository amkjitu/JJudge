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
@EnableConfigurationProperties(JwtProperties.class)
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_GET_PATHS = {
            "/api/v1/problems", "/api/v1/problems/**",
            "/api/v1/tags", "/api/v1/tags/**",
            "/api/v1/users/*", "/api/v1/users/*/submissions",
            "/api/v1/reports/**"
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
     * Everything outside {@code /api}: Swagger, actuator, and the OAuth2 login redirect flow.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http,
                                              ProblemDetailAuthenticationHandlers handlers,
                                              ObjectProvider<ClientRegistrationRepository> clientRegistrations,
                                              ObjectProvider<OAuth2LoginSuccessHandler> oauth2SuccessHandler)
            throws Exception {
        http
                // Without this, an anonymous request to a protected actuator endpoint gets a
                // bare 403: with no authentication mechanism configured on this chain, Spring
                // has nothing to challenge with and reports "denied" rather than "who are you?".
                // 401 is the honest answer, and it keeps the error shape uniform.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/", "/error", "/login/**", "/oauth2/**").permitAll()
                        .anyRequest().permitAll());

        // Google login is optional configuration. Without a client registration there is no
        // ClientRegistrationRepository bean, and calling oauth2Login() would fail at startup -
        // so the whole application would refuse to boot just because a demo deployment has no
        // Google credentials.
        ClientRegistrationRepository registrations = clientRegistrations.getIfAvailable();
        if (registrations != null) {
            OAuth2LoginSuccessHandler successHandler = oauth2SuccessHandler.getObject();
            http.oauth2Login(oauth2 -> oauth2.successHandler(successHandler));
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
