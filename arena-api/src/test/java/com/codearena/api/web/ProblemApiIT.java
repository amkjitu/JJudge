package com.codearena.api.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through the real Spring context and a real PostgreSQL, against the seeded
 * catalogue. Complements {@link ProblemControllerTest}, which mocks the service away: these
 * exercise the Specification SQL, the entity graphs, the database constraints and - because
 * there is no test-managed transaction - the real lazy-loading behaviour.
 */
@DisplayName("Problem API")
class ProblemApiIT extends AbstractApiIT {

    @Nested
    @DisplayName("listing and filtering")
    class Listing {

        @Test
        @DisplayName("pages the seeded catalogue")
        void pagesCatalogue() throws Exception {
            mockMvc.perform(get("/api/v1/problems").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(40))
                    .andExpect(jsonPath("$.totalPages").value(4))
                    .andExpect(jsonPath("$.content", hasSize(10)))
                    .andExpect(jsonPath("$.last").value(false));
        }

        @Test
        @DisplayName("serialises tags without an open session (open-in-view is off)")
        void tagsSurviveSessionClose() throws Exception {
            // Regression guard: this is the exact request that failed in production with
            // LazyInitializationException while the transactional version of this test passed.
            mockMvc.perform(get("/api/v1/problems").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].tags", everyItem(hasSize(greaterThan(0)))));
        }

        @Test
        @DisplayName("filters by tag through the join, without duplicating rows")
        void filtersByTag() throws Exception {
            mockMvc.perform(get("/api/v1/problems").param("tag", "dp").param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].tags", everyItem(hasItem("dp"))))
                    .andExpect(jsonPath("$.content[*].slug", hasSize(greaterThan(0))));
        }

        @Test
        @DisplayName("tag matching is case-insensitive")
        void tagFilterIsCaseInsensitive() throws Exception {
            String lower = mockMvc.perform(get("/api/v1/problems").param("tag", "graph"))
                    .andReturn().getResponse().getContentAsString();
            String upper = mockMvc.perform(get("/api/v1/problems").param("tag", "GRAPH"))
                    .andReturn().getResponse().getContentAsString();

            assertThat(upper).isEqualTo(lower);
        }

        @Test
        @DisplayName("filters by rating band")
        void filtersByRatingBand() throws Exception {
            mockMvc.perform(get("/api/v1/problems")
                            .param("minRating", "1300")
                            .param("maxRating", "1500")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].rating", everyItem(greaterThanOrEqualTo(1300))))
                    .andExpect(jsonPath("$.content[*].rating", everyItem(lessThanOrEqualTo(1500))));
        }

        @Test
        @DisplayName("combines filters with AND")
        void combinesFilters() throws Exception {
            mockMvc.perform(get("/api/v1/problems")
                            .param("tag", "dp")
                            .param("difficulty", "HARD")
                            .param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].difficulty", everyItem(is("HARD"))))
                    .andExpect(jsonPath("$.content[*].tags", everyItem(hasItem("dp"))));
        }

        @Test
        @DisplayName("searches title and slug")
        void searchesTitleAndSlug() throws Exception {
            mockMvc.perform(get("/api/v1/problems").param("search", "dijkstra"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].slug").value("dijkstra-on-a-weighted-grid"));
        }

        @Test
        @DisplayName("LIKE wildcards in the search term are escaped, not honoured")
        void searchEscapesWildcards() throws Exception {
            // If '%' leaked into the LIKE pattern unescaped this would match everything.
            mockMvc.perform(get("/api/v1/problems").param("search", "%"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("a page size beyond the configured cap is clamped, not honoured")
        void pageSizeIsCapped() throws Exception {
            mockMvc.perform(get("/api/v1/problems").param("size", "100000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(100));
        }

        @Test
        @DisplayName("sorting by an unknown property is a 400, not a 500")
        void unknownSortPropertyIsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/problems").param("sort", "notAField"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("notAField")));
        }
    }

    @Nested
    @DisplayName("detail")
    class Detail {

        @Test
        @DisplayName("returns the full record with its tags")
        void returnsDetail() throws Exception {
            mockMvc.perform(get("/api/v1/problems/dijkstra-on-a-weighted-grid"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Dijkstra on a Weighted Grid"))
                    .andExpect(jsonPath("$.rating").value(1500))
                    .andExpect(jsonPath("$.difficulty").value("MEDIUM"))
                    .andExpect(jsonPath("$.timeLimitMs").value(3000))
                    .andExpect(jsonPath("$.tags", hasSize(3)))
                    .andExpect(jsonPath("$.createdAt").exists());
        }

        @Test
        @DisplayName("unknown slug is a 404 problem detail")
        void unknownSlug() throws Exception {
            mockMvc.perform(get("/api/v1/problems/no-such-problem"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/resource-not-found"));
        }
    }

    /**
     * These tests write. Because there is no test transaction to roll back, each one creates
     * the problem it is going to mutate rather than touching seeded data; the base class
     * deletes anything created above the id watermark afterwards.
     */
    @Nested
    @DisplayName("admin CRUD")
    class AdminCrud {

        private String createBody(String slug, int rating, List<String> tags) throws Exception {
            return objectMapper.writeValueAsString(Map.of(
                    "title", "Fixture Problem",
                    "slug", slug,
                    "rating", rating,
                    "tags", tags));
        }

        private void createFixture(String slug, int rating, List<String> tags) throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(slug, rating, tags)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("creates a problem and derives its difficulty from the rating")
        void createsProblem() throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("fixture-create", 1850, List.of("dp", "graph"))))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location",
                            "http://localhost/api/v1/problems/fixture-create"))
                    .andExpect(jsonPath("$.difficulty").value("HARD"))
                    .andExpect(jsonPath("$.timeLimitMs").value(1000))
                    .andExpect(jsonPath("$.tags", hasSize(2)));

            mockMvc.perform(get("/api/v1/problems/fixture-create"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tags", hasSize(2)));
        }

        @Test
        @DisplayName("rejects a slug that is already taken")
        void rejectsDuplicateSlug() throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("edit-distance", 1500, List.of("dp"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/resource-conflict"))
                    .andExpect(jsonPath("$.identifier").value("edit-distance"));
        }

        @Test
        @DisplayName("rejects an unknown tag with the offending name in the message")
        void rejectsUnknownTag() throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("fixture-unknown-tag", 1500, List.of("dp", "quantum"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail", containsString("quantum")));
        }

        @Test
        @DisplayName("rejects a malformed slug")
        void rejectsMalformedSlug() throws Exception {
            mockMvc.perform(post("/api/v1/admin/problems")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody("Not A Slug", 1500, List.of("dp"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("slug"));
        }

        @Test
        @DisplayName("updates a problem, recomputing difficulty and replacing tags")
        void updatesProblem() throws Exception {
            createFixture("fixture-update", 1500, List.of("dp", "graph"));

            String body = objectMapper.writeValueAsString(Map.of(
                    "title", "Fixture Problem (revised)",
                    "rating", 900,
                    "tags", List.of("strings")));

            mockMvc.perform(put("/api/v1/admin/problems/fixture-update")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Fixture Problem (revised)"))
                    .andExpect(jsonPath("$.difficulty").value("EASY"))
                    .andExpect(jsonPath("$.slug").value("fixture-update"))
                    .andExpect(jsonPath("$.tags", hasSize(1)))
                    .andExpect(jsonPath("$.tags[0]").value("strings"));

            // and the change is actually persisted, not just reflected in the response
            mockMvc.perform(get("/api/v1/problems/fixture-update"))
                    .andExpect(jsonPath("$.rating").value(900))
                    .andExpect(jsonPath("$.tags[0]").value("strings"));
        }

        @Test
        @DisplayName("updating something that does not exist is a 404")
        void updateUnknown() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "title", "Nope", "rating", 900, "tags", List.of("strings")));

            mockMvc.perform(put("/api/v1/admin/problems/never-existed")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("deletes a problem")
        void deletesProblem() throws Exception {
            createFixture("fixture-delete", 1500, List.of("dp"));

            mockMvc.perform(delete("/api/v1/admin/problems/fixture-delete"))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/problems/fixture-delete"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("deleting something that is not there is a 404")
        void deleteUnknown() throws Exception {
            mockMvc.perform(delete("/api/v1/admin/problems/never-existed"))
                    .andExpect(status().isNotFound());
        }
    }
}
