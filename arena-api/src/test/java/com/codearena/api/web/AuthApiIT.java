package com.codearena.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real token lifecycle, end to end: register, log in, call a protected endpoint with the
 * issued bearer token, rotate it, and prove that a rotated token cannot be used again.
 *
 * <p>Unlike the rest of the suite, these do not use {@code asUser(...)} - the point is to
 * exercise the genuine signing, decoding and rotation paths rather than to inject a principal.
 */
@DisplayName("Auth API")
class AuthApiIT extends AbstractApiIT {

    private static final String PASSWORD = "Password123!";

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode login(String usernameOrEmail, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "usernameOrEmail", usernameOrEmail,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result);
    }

    private static String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asText();
    }

    @Nested
    @DisplayName("registration")
    class Registration {

        private String registerBody(String username, String email) throws Exception {
            return objectMapper.writeValueAsString(Map.of(
                    "username", username, "email", email, "password", "a-long-enough-password"));
        }

        @Test
        @DisplayName("creates an account and returns a usable token pair")
        void registersAndReturnsTokens() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody("newcomer", "newcomer@example.com")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.username").value("newcomer"))
                    .andReturn();

            // The token works immediately - no separate login round trip needed.
            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(json(result))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("newcomer"))
                    .andExpect(jsonPath("$.rating").value(1200));
        }

        @Test
        @DisplayName("never echoes the password back in any form")
        void doesNotEchoPassword() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody("quiet", "quiet@example.com")))
                    .andExpect(status().isCreated())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("a-long-enough-password", "passwordHash", "$2a$");
        }

        @Test
        @DisplayName("rejects a duplicate username with 409")
        void duplicateUsername() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody("bob", "different@example.com")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.identifier").value("bob"));
        }

        @Test
        @DisplayName("rejects a duplicate email with 409")
        void duplicateEmail() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(registerBody("brandnew", "bob@codearena.dev")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.identifier").value("bob@codearena.dev"));
        }

        @Test
        @DisplayName("rejects a short password with a field-level error")
        void shortPassword() throws Exception {
            mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "shorty", "email", "shorty@example.com",
                                    "password", "short"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("password"));
        }

        @Test
        @DisplayName("a validation error never echoes the rejected password")
        void validationDoesNotEchoPassword() throws Exception {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "username", "shorty", "email", "not-an-email",
                                    "password", "hunter2"))))
                    .andExpect(status().isBadRequest())
                    .andReturn();

            // rejectedValue is echoed for most fields; the password must not be one of them.
            JsonNode errors = json(result).get("errors");
            for (JsonNode error : errors) {
                if ("password".equals(error.get("field").asText())) {
                    assertThat(error.has("rejectedValue"))
                            .as("the rejected password value must not be echoed back")
                            .isFalse();
                }
            }
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("accepts a username")
        void byUsername() throws Exception {
            assertThat(login("bob", PASSWORD).get("username").asText()).isEqualTo("bob");
        }

        @Test
        @DisplayName("accepts an email address too")
        void byEmail() throws Exception {
            assertThat(login("bob@codearena.dev", PASSWORD).get("username").asText()).isEqualTo("bob");
        }

        @Test
        @DisplayName("a wrong password is 401, not 403 or 500")
        void wrongPassword() throws Exception {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "usernameOrEmail", "bob", "password", "not-the-password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type")
                            .value("https://codearena.dev/errors/invalid-credentials"));
        }

        @Test
        @DisplayName("an unknown user is indistinguishable from a wrong password")
        void unknownUserLooksIdenticalToWrongPassword() throws Exception {
            // Any difference here is a free account-enumeration oracle.
            String unknownUser = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "usernameOrEmail", "no-such-person", "password", "whatever12345"))))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "usernameOrEmail", "bob", "password", "whatever12345"))))
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            assertThat(objectMapper.readTree(unknownUser).get("detail"))
                    .isEqualTo(objectMapper.readTree(wrongPassword).get("detail"));
        }
    }

    @Nested
    @DisplayName("bearer tokens")
    class BearerTokens {

        @Test
        @DisplayName("a real issued token authorises a protected endpoint")
        void tokenAuthorises() throws Exception {
            JsonNode tokens = login("bob", PASSWORD);

            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokens)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value("bob"))
                    .andExpect(jsonPath("$.solvedCount").value(14));
        }

        @Test
        @DisplayName("no token is 401 with an RFC 7807 body, not an empty response")
        void missingTokenIsProblemDetail() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type")
                            .value("https://codearena.dev/errors/unauthenticated"))
                    .andExpect(jsonPath("$.title").value("Authentication required"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("a garbage token is 401")
        void garbageTokenIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a token signed with the wrong key is 401")
        void forgedTokenIsRejected() throws Exception {
            // Header/payload lifted from a valid token, signature replaced.
            JsonNode tokens = login("bob", PASSWORD);
            String valid = tokens.get("accessToken").asText();
            String forged = valid.substring(0, valid.lastIndexOf('.')) + ".ZmFrZS1zaWduYXR1cmU";

            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("refresh rotation")
    class Rotation {

        @Test
        @DisplayName("rotates: a refresh returns a different refresh token")
        void refreshRotates() throws Exception {
            JsonNode first = login("bob", PASSWORD);
            String originalRefresh = first.get("refreshToken").asText();

            MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("refreshToken", originalRefresh))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty())
                    .andReturn();

            String rotated = json(result).get("refreshToken").asText();
            assertThat(rotated).isNotEqualTo(originalRefresh);

            // and the new one works
            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(json(result))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("the rotated cookie replaces the consumed one")
        void refreshUpdatesTheCookie() throws Exception {
            JsonNode tokens = login("bob", PASSWORD);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("refreshToken", tokens.get("refreshToken").asText()))))
                    .andExpect(status().isOk())
                    .andExpect(cookie().exists("arena_refresh"))
                    .andExpect(cookie().httpOnly("arena_refresh", true));
        }

        @Test
        @DisplayName("the refresh token may arrive in the cookie instead of the body")
        void refreshAcceptsCookie() throws Exception {
            JsonNode tokens = login("bob", PASSWORD);

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .cookie(new jakarta.servlet.http.Cookie(
                                    "arena_refresh", tokens.get("refreshToken").asText())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isNotEmpty());
        }

        @Test
        @DisplayName("reusing a rotated token is refused and revokes every session")
        void reuseDetectionRevokesEverything() throws Exception {
            JsonNode first = login("bob", PASSWORD);
            String stolen = first.get("refreshToken").asText();

            // Legitimate rotation.
            MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", stolen))))
                    .andExpect(status().isOk())
                    .andReturn();
            String live = json(rotated).get("refreshToken").asText();

            // The attacker replays the old value.
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", stolen))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail", containsString("already been used")));

            // ... which also kills the token the legitimate client was holding.
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", live))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an unrecognised refresh token is 401")
        void unknownRefreshToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("refreshToken", "not-a-real-token"))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refreshing with nothing at all is 400, not 500")
        void missingRefreshToken() throws Exception {
            mockMvc.perform(post("/api/v1/auth/refresh"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("refresh token is required")));
        }
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("revokes the refresh token and clears the cookie")
        void logoutRevokes() throws Exception {
            JsonNode tokens = login("bob", PASSWORD);
            String refresh = tokens.get("refreshToken").asText();

            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                    .andExpect(status().isNoContent())
                    .andExpect(cookie().maxAge("arena_refresh", 0));

            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("is idempotent - logging out twice is not an error")
        void logoutIsIdempotent() throws Exception {
            JsonNode tokens = login("bob", PASSWORD);
            String body = objectMapper.writeValueAsString(
                    Map.of("refreshToken", tokens.get("refreshToken").asText()));

            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("does not invalidate the access token, which simply expires")
        void accessTokenOutlivesLogout() throws Exception {
            // Stated plainly because it is the real trade-off of stateless access tokens, and
            // it is why their TTL is 15 minutes rather than a day.
            JsonNode tokens = login("bob", PASSWORD);

            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("refreshToken", tokens.get("refreshToken").asText()))))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/auth/me")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokens)))
                    .andExpect(status().isOk());
        }
    }
}
