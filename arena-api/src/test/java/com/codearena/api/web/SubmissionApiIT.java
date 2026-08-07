package com.codearena.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writes here are made as {@code alice}. {@code bob}'s counts are asserted exactly elsewhere in
 * this class, and with no test transaction to roll back, submitting as him would make those
 * assertions depend on execution order.
 */
@DisplayName("Submission and user API")
class SubmissionApiIT extends AbstractApiIT {

    private static final String SUBMITTER = "alice";

    private String submissionBody(String slug) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "problemSlug", slug,
                "language", "JAVA",
                "sourceCode", "public class Main { public static void main(String[] a) {} }"));
    }

    private String submitAs(String username, String slug) throws Exception {
        return mockMvc.perform(post("/api/v1/submissions")
                        .with(asUser(username))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionBody(slug)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
    }

    @Test
    @DisplayName("a submission is persisted as QUEUED and readable back")
    void submitAndReadBack() throws Exception {
        String location = submitAs(SUBMITTER, "edit-distance");
        String path = URI.create(location).getPath();

        // Read back in a *separate* request, so the associations are resolved by the entity
        // graph rather than by a session left open from the write.
        mockMvc.perform(get(path).with(asUser(SUBMITTER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(SUBMITTER))
                .andExpect(jsonPath("$.problemSlug").value("edit-distance"))
                .andExpect(jsonPath("$.problemTitle").value("Edit Distance"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.verdict").doesNotExist())
                .andExpect(jsonPath("$.submittedAt").exists());
    }

    @Test
    @DisplayName("submitted source is retrievable as plain text")
    void sourceIsRetrievable() throws Exception {
        String location = submitAs(SUBMITTER, "edit-distance");

        mockMvc.perform(get(URI.create(location).getPath() + "/source").with(asUser(SUBMITTER)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string(containsString("class Main")));
    }

    @Test
    @DisplayName("submitting against a problem that does not exist is a 404")
    void unknownProblemIs404() throws Exception {
        mockMvc.perform(post("/api/v1/submissions")
                        .with(asUser(SUBMITTER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionBody("no-such-problem")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("Problem"));
    }

    @Test
    @DisplayName("an authenticated principal with no account row is a 404 naming the user")
    void unknownUserIs404() throws Exception {
        // Can only happen if an account is deleted while its access token is still valid -
        // which is exactly why the service resolves the user rather than trusting the token.
        mockMvc.perform(post("/api/v1/submissions")
                        .with(asUser("deleted-account"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submissionBody("edit-distance")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("User"));
    }

    @Test
    @DisplayName("history is paged newest-first for the calling user")
    void historyForCurrentUser() throws Exception {
        mockMvc.perform(get("/api/v1/submissions/me")
                        .with(asUser("bob"))
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.totalElements", greaterThan(5)))
                .andExpect(jsonPath("$.content[*].username", everyItem(is("bob"))))
                // the flattened problem fields require the entity graph to have loaded
                .andExpect(jsonPath("$.content[*].problemTitle", everyItem(not(blankOrNullString()))));
    }

    @Test
    @DisplayName("submissions can be listed per problem without an account")
    void submissionsPerProblem() throws Exception {
        mockMvc.perform(get("/api/v1/problems/coin-change-minimum/submissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].problemSlug",
                        everyItem(is("coin-change-minimum"))));
    }

    @Test
    @DisplayName("profile reports solve counts and orders topics weakest-first")
    void profileOrdersWeakestTopicsFirst() throws Exception {
        String body = mockMvc.perform(get("/api/v1/users/bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.rating").value(1450))
                .andExpect(jsonPath("$.solvedCount").value(14))
                .andExpect(jsonPath("$.submissionCount").value(22))
                .andExpect(jsonPath("$.tagStats", hasSize(greaterThan(0))))
                .andReturn().getResponse().getContentAsString();

        var stats = objectMapper.readTree(body).get("tagStats");
        double first = stats.get(0).get("proficiency").asDouble();
        double last = stats.get(stats.size() - 1).get("proficiency").asDouble();
        assertThat(first).isLessThanOrEqualTo(last);
    }

    @Test
    @DisplayName("a public profile never exposes the password hash or email")
    void profileDoesNotLeakCredentials() throws Exception {
        String body = mockMvc.perform(get("/api/v1/users/bob"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash", "$2a$", "bob@codearena.dev");
    }

    @Test
    @DisplayName("an unknown user profile is a 404")
    void unknownProfile() throws Exception {
        mockMvc.perform(get("/api/v1/users/nobody"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the tag endpoint exposes the prerequisite DAG")
    void tagsExposePrerequisites() throws Exception {
        mockMvc.perform(get("/api/v1/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)))
                .andExpect(jsonPath("$[?(@.name == 'shortest-path')].prerequisites[*]",
                        containsInAnyOrder("bfs", "heap")));
    }
}
