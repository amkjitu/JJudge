package com.codearena.api.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Registers the Google client only when credentials are actually configured.
 *
 * <p>Spring Boot's own OAuth2 client auto-configuration keys off
 * {@code spring.security.oauth2.client.registration.google.client-id} being <em>present</em>,
 * and then asserts it is non-empty. Passing the variable through Docker Compose always makes
 * it present - as an empty string when the operator has no Google credentials - which turns a
 * missing optional feature into a startup failure for the whole application.
 *
 * <p>{@code @ConditionalOnExpression} on the resolved value avoids that: no credentials means
 * no bean, no bean means {@link SecurityConfig} skips {@code oauth2Login()}, and the rest of
 * the application starts normally.
 */
@Configuration
@ConditionalOnExpression("!'${arena.oauth2.google.client-id:}'.isBlank()")
public class GoogleOAuth2Config {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${arena.oauth2.google.client-id}") String clientId,
            @Value("${arena.oauth2.google.client-secret}") String clientSecret) {

        // CommonOAuth2Provider supplies Google's endpoints, scopes and the
        // {baseUrl}/login/oauth2/code/{registrationId} redirect template.
        ClientRegistration google = CommonOAuth2Provider.GOOGLE
                .getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();

        return new InMemoryClientRegistrationRepository(google);
    }
}
