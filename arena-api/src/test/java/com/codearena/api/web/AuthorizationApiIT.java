package com.codearena.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization matrix, asserted against the real filter chain.
 *
 * <p>Deliberately exhaustive about the <em>negative</em> cases. A test proving an admin can
 * create a problem says nothing about whether everyone else can too, and that second question
 * is the one that matters.
 */
@DisplayName("Authorization")
class AuthorizationApiIT extends AbstractApiIT {

    private String problemBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "title", "Fixture", "slug", "fixture-authz", "rating", 1200,
                "tags", List.of("dp")));
    }

    @Nested
    @DisplayName("public endpoints need no account")
    class PublicEndpoints {

        @Test
        @DisplayName("browsing problems")
        void problems() throws Exception {
            mockMvc.perform(get("/api/v1/problems")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/problems/edit-distance")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/problems/edit-distance/submissions")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("tags, profiles and reports")
        void otherReads() throws Exception {
            mockMvc.perform(get("/api/v1/tags")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/users/bob")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/users/bob/submissions")).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/reports/tag-difficulty")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("health and API docs")
        void infrastructure() throws Exception {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("submission endpoints require authentication")
    class SubmissionEndpoints {

        @Test
        @DisplayName("anonymous submission is 401")
        void anonymousPostIs401() throws Exception {
            mockMvc.perform(post("/api/v1/submissions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "problemSlug", "edit-distance",
                                    "language", "JAVA",
                                    "sourceCode", "class Main {}"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/unauthenticated"));
        }

        @Test
        @DisplayName("anonymous history is 401")
        void anonymousHistoryIs401() throws Exception {
            mockMvc.perform(get("/api/v1/submissions/me")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("anonymous read of a single submission is 401")
        void anonymousSubmissionIs401() throws Exception {
            mockMvc.perform(get("/api/v1/submissions/1")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an ordinary user may read submissions")
        void userMayRead() throws Exception {
            mockMvc.perform(get("/api/v1/submissions/me").with(asUser("bob")))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("admin endpoints require the ADMIN role")
    class AdminEndpoints {

        @Test
        @DisplayName("anonymous create is 401")
        void anonymousIs401() throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(problemBody()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("an authenticated non-admin is 403, not 401")
        void userIs403() throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .with(asUser("bob"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(problemBody()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/forbidden"));
        }

        @Test
        @DisplayName("a non-admin cannot update either")
        void userCannotUpdate() throws Exception {
            mockMvc.perform(put("/api/v1/admin/problems/edit-distance")
                            .with(asUser("bob"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "Hijacked", "rating", 800, "tags", List.of("dp")))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a non-admin cannot delete either")
        void userCannotDelete() throws Exception {
            mockMvc.perform(delete("/api/v1/admin/problems/edit-distance").with(asUser("bob")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("an admin may create")
        void adminMayCreate() throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .with(asAdmin("admin"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(problemBody()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("the refused write really did not happen")
        void refusedWriteHasNoEffect() throws Exception {
            mockMvc.perform(put("/api/v1/admin/problems/edit-distance")
                            .with(asUser("bob"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "Hijacked", "rating", 800, "tags", List.of("dp")))))
                    .andExpect(status().isForbidden());

            mockMvc.perform(get("/api/v1/problems/edit-distance"))
                    .andExpect(jsonPath("$.title").value("Edit Distance"))
                    .andExpect(jsonPath("$.rating").value(1500));
        }
    }

    @Nested
    @DisplayName("actuator")
    class Actuator {

        @Test
        @DisplayName("metrics are exposed but locked to ADMIN")
        void metricsRequireAdmin() throws Exception {
            mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/actuator/metrics").with(asUser("bob"))).andExpect(status().isForbidden());
            mockMvc.perform(get("/actuator/metrics").with(asAdmin("admin"))).andExpect(status().isOk());
        }
    }
}
