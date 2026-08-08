package com.codearena.api.ui;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A permissive filter chain for {@code @WebMvcTest} UI slices.
 *
 * <p>The real {@link com.codearena.api.security.SecurityConfig} is a plain
 * {@code @Configuration}, which slice tests do not scan, so without something here Boot's
 * default chain applies and every request comes back 401. Importing the real one instead would
 * drag in the JWT decoder, the OAuth2 client registry and the whole persistence layer - none of
 * which a template-rendering test has any use for.
 *
 * <p>The filters stay <em>enabled</em> rather than being switched off with
 * {@code addFilters = false}: they are what populates {@code request.getUserPrincipal()}, and
 * without them every controller taking an {@code Authentication} parameter would receive null
 * even under {@code @WithMockUser}. Authorisation itself is covered by
 * {@code AuthorizationApiIT} against the real chain.
 *
 * <p>CSRF is left <em>on</em> for the same reason. Disabling it would be the easy way to make
 * the POST tests pass, but Thymeleaf only injects the hidden {@code _csrf} input when a
 * {@code CsrfToken} is on the request - so a slice with CSRF disabled silently stops testing
 * whether the forms carry one, which is the whole point of having it.
 */
@TestConfiguration
public class UiSliceSecurityConfig {

    @Bean
    SecurityFilterChain permissiveChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
