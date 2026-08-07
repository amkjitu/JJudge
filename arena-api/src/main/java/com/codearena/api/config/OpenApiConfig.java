package com.codearena.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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

                                **Authentication is not wired up until Phase 3.** Until then the
                                calling user is taken from an optional `X-Arena-User` header,
                                defaulting to the `bob` demo account.
                                """)
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT"))
                        .contact(new Contact().name("CodeArena")))
                .servers(List.of(new Server().url("/").description("This instance")));
    }
}
