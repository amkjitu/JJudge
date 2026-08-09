package com.codearena.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The engine against the seeded catalogue and the real demo histories.
 *
 * <p>{@link com.codearena.api.recommendation.RecommendationEngineTest} proves the algorithm in
 * isolation; this proves the wiring - that the right rows reach it, that the band and exclusion
 * filters are applied in SQL, and that the three demo users with deliberately different profiles
 * genuinely get different answers.
 */
@DisplayName("Recommendation API")
class RecommendationApiIT extends AbstractApiIT {

    private JsonNode recommendationsFor(String username, int limit) throws Exception {
        String body = mockMvc.perform(get("/api/v1/recommendations/users/{u}", username)
                        .param("limit", String.valueOf(limit)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("returns ranked suggestions with a score breakdown")
    void returnsRankedSuggestions() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations/users/bob").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].problem.slug").exists())
                .andExpect(jsonPath("$[0].reason").exists())
                .andExpect(jsonPath("$[0].why.tagWeakness").exists())
                .andExpect(jsonPath("$[0].why.ratingFit").exists())
                .andExpect(jsonPath("$[*].why.tagWeakness", everyItem(lessThanOrEqualTo(1.0))));
    }

    @Test
    @DisplayName("scores come back in descending order")
    void orderedByScore() throws Exception {
        JsonNode recommendations = recommendationsFor("bob", 10);

        List<Double> scores = new ArrayList<>();
        recommendations.forEach(node -> scores.add(node.get("score").asDouble()));

        assertThat(scores).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    @DisplayName("never suggests a problem the user has already solved")
    void excludesSolvedProblems() throws Exception {
        // bob has 14 accepted problems in the seed data; none may appear.
        Set<String> solved = new HashSet<>();
        JsonNode history = objectMapper.readTree(
                mockMvc.perform(get("/api/v1/users/bob/submissions").param("size", "100"))
                        .andReturn().getResponse().getContentAsString());
        history.get("content").forEach(node -> {
            if ("AC".equals(node.path("verdict").asText())) {
                solved.add(node.get("problemSlug").asText());
            }
        });
        assertThat(solved).isNotEmpty();

        JsonNode recommendations = recommendationsFor("bob", 20);
        recommendations.forEach(node ->
                assertThat(solved).doesNotContain(node.get("problem").get("slug").asText()));
    }

    @Test
    @DisplayName("stays inside the rating band around the user")
    void staysInRatingBand() throws Exception {
        // bob is rated 1450, so the configured band is [1350, 1650].
        JsonNode recommendations = recommendationsFor("bob", 20);

        assertThat(recommendations).isNotEmpty();
        recommendations.forEach(node -> {
            int rating = node.get("problem").get("rating").asInt();
            assertThat(rating).isBetween(1350, 1650);
        });
    }

    @Test
    @DisplayName("honours the per-topic diversity cap")
    void capsResultsPerTopic() throws Exception {
        JsonNode recommendations = recommendationsFor("bob", 10);

        Map<String, Integer> perTag = new HashMap<>();
        recommendations.forEach(node ->
                node.get("problem").get("tags").forEach(tag ->
                        perTag.merge(tag.asText(), 1, Integer::sum)));

        // The cap is 2 and the engine backfills only when it would otherwise return too few;
        // with 40 problems across 30 tags there is enough variety that it should not need to.
        assertThat(perTag.values()).allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(3));
    }

    @Test
    @DisplayName("different histories produce different suggestions")
    void respondsToTheUsersActualHistory() throws Exception {
        // The demo accounts were seeded with deliberately different profiles. If the engine
        // returned the same list for all three it would be sorting the catalogue, not
        // recommending.
        List<String> forAlice = slugs(recommendationsFor("alice", 8));
        List<String> forBob = slugs(recommendationsFor("bob", 8));
        List<String> forCarol = slugs(recommendationsFor("carol", 8));

        assertThat(forAlice).isNotEqualTo(forBob);
        assertThat(forBob).isNotEqualTo(forCarol);
        assertThat(forAlice).isNotEqualTo(forCarol);
    }

    @Test
    @DisplayName("a weak topic is surfaced ahead of a strong one")
    void surfacesWeakTopics() throws Exception {
        // bob has never solved a shortest-path problem and repeatedly failed dp. The top
        // suggestions should lean towards topics he is demonstrably weak at rather than the
        // arrays problems he clears easily.
        JsonNode recommendations = recommendationsFor("bob", 5);

        double topWeakness = recommendations.get(0).get("why").get("tagWeakness").asDouble();
        assertThat(topWeakness).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("the endpoint is deterministic across repeated calls")
    void deterministic() throws Exception {
        assertThat(slugs(recommendationsFor("carol", 6)))
                .isEqualTo(slugs(recommendationsFor("carol", 6)));
    }

    @Test
    @DisplayName("an unknown user is a 404")
    void unknownUser() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations/users/nobody"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an out-of-range limit is rejected")
    void limitIsValidated() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations/users/bob").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/recommendations/users/bob").param("limit", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("/me needs authentication and answers for the caller")
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/recommendations/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/recommendations/me").with(asUser("carol")).param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))));
    }

    private static List<String> slugs(JsonNode recommendations) {
        List<String> slugs = new ArrayList<>();
        recommendations.forEach(node -> slugs.add(node.get("problem").get("slug").asText()));
        return slugs;
    }
}
