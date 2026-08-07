package com.codearena.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI codeArenaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CodeArena API")
                        .version("v1")
                        .description("""
                                Competitive programming practice platform.

                                Errors follow RFC 7807: failures return `application/problem+json`
                                with a stable `type` URI, a `title`, a human-readable `detail` and,
                                for validation failures, an `errors` array of field-level violations.

                                ### Authentication

                                `POST /api/v1/auth/login` returns a short-lived JWT access token and
                                a long-lived, single-use refresh token. Send the access token as
                                `Authorization: Bearer <token>` — click **Authorize** above to do
                                that from this page.

                                Browsing problems, tags and reports needs no account. Submitting
                                does. `/api/v1/admin/**` requires the ADMIN role.
                                """)
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT"))
                        .contact(new Contact().name("CodeArena")))
                .servers(List.of(new Server().url("/").description("This instance")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token from POST /api/v1/auth/login")));
    }
}
