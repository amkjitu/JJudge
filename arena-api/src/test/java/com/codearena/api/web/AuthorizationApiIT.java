package com.codearena.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    @DisplayName("assist endpoints require authentication")
    class AssistEndpoints {

        @Test
        @DisplayName("anonymous hint over the API is 401")
        void anonymousApiHint() throws Exception {
            mockMvc.perform(get("/api/v1/assist/problems/edit-distance/hint"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("anonymous fetch of the page's hint route is 401, not a login redirect")
        void anonymousWebHint() throws Exception {
            // The page route sits under /problems, which is otherwise public - but the pattern is
            // /problems/*, and a single * does not cross a slash, so /problems/{slug}/hint falls
            // through to the chain's authenticated default. That is a property of the matcher
            // rather than an explicit rule, which is exactly why it is worth pinning down: adding
            // /problems/** to the public list later would silently open this up.
            //
            // 401 rather than a 302: Spring's default entry point only sends browsers to the
            // login form, and a request that does not ask for HTML gets a status code instead.
            // That is the right answer here - a `fetch` cannot follow a redirect to a login page,
            // and the script needs a code it can act on.
            mockMvc.perform(get("/problems/edit-distance/hint"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a browser navigating to the same route is sent to the login page")
        void anonymousWebHintInABrowser() throws Exception {
            mockMvc.perform(get("/problems/edit-distance/hint").accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }

        @Test
        @DisplayName("anonymous complexity analysis is 401")
        void anonymousComplexity() throws Exception {
            mockMvc.perform(get("/api/v1/assist/submissions/1/complexity"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("complexity analysis of someone else's submission is 403")
        void complexityOfAnotherUsersSubmission() throws Exception {
            // The hole this closes: the endpoint originally relied on getById to reject the
            // request, and getById does no such thing - it is a deliberately public read, because
            // anyone may see that a submission exists and what verdict it got. The source behind
            // it is private, and so is an analysis of it, so the check has to be at the endpoint.
            // Authenticating the caller is not the same as authorising them for this row.
            long id = seededSubmissionIdFor("carol");

            mockMvc.perform(get("/api/v1/assist/submissions/{id}/complexity", id).with(asUser("bob")))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("the submission page's live-update routes are on the session chain")
    class LiveUpdateRoutes {

        /**
         * The bug these pin down: {@code verdict-stream.js} originally called
         * {@code /api/v1/submissions/{id}} and its stream. That chain is stateless and
         * bearer-only, so a browser carrying a session cookie and no token was anonymous there -
         * the stream 401'd, the poll fallback 401'd too, and the badge never updated. Nothing
         * failed loudly, and the existing tests could not see it: MockMvc's {@code with(user())}
         * installs the security context directly rather than exercising the chain.
         *
         * <p>So these assert the routes the page actually uses, and that they are reachable the
         * way the page reaches them.
         */
        @Test
        @DisplayName("the page's status route serves JSON to its owner")
        void statusForOwner() throws Exception {
            long id = seededSubmissionIdFor("carol");

            mockMvc.perform(get("/submissions/{id}/status", id).with(asUser("carol")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.username").value("carol"));
        }

        @Test
        @DisplayName("the page's stream route opens for its owner")
        void streamForOwner() throws Exception {
            long id = seededSubmissionIdFor("carol");

            mockMvc.perform(get("/submissions/{id}/stream", id).with(asUser("carol")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("another user cannot read either")
        void notForOthers() throws Exception {
            long id = seededSubmissionIdFor("carol");

            mockMvc.perform(get("/submissions/{id}/status", id).with(asUser("bob")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/submissions/{id}/stream", id).with(asUser("bob")))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("anonymous callers get a status code, not a login redirect")
        void anonymous() throws Exception {
            mockMvc.perform(get("/submissions/1/status")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a submission whose source was never archived still renders its page")
        void pageRendersWithoutArchivedSource() throws Exception {
            // Seeded history predates the archive, so its source is not in MongoDB. Insisting on
            // source made the whole page a 404 - losing the verdict, runtime and problem, which
            // are all still there and all still worth showing.
            long id = seededSubmissionIdFor("carol");

            mockMvc.perform(get("/submissions/{id}", id).with(asUser("carol")))
                    .andExpect(status().isOk())
                    .andExpect(content().string(
                            org.hamcrest.Matchers.containsString("No source is archived")));
        }
    }

    private long seededSubmissionIdFor(String username) {
        Long id = jdbcTemplate.queryForObject("""
                SELECT s.id FROM submissions s JOIN users u ON u.id = s.user_id
                WHERE u.username = ? ORDER BY s.id LIMIT 1
                """, Long.class, username);
        if (id == null) {
            throw new IllegalStateException("No seeded submission for " + username);
        }
        return id;
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
