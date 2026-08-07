package com.codearena.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the one endpoint backed by Spring JDBC rather than JPA. Runs against real
 * PostgreSQL because the query leans on {@code FILTER (WHERE ...)}, {@code bool_or} and
 * {@code NULLS LAST} - none of which an in-memory database would accept.
 */
@DisplayName("GET /api/v1/reports/tag-difficulty")
class ReportApiIT extends AbstractApiIT {

    private JsonNode report(String... params) throws Exception {
        var request = get("/api/v1/reports/tag-difficulty");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("returns one row per tag, including tags nobody has attempted")
    void coversEveryTag() throws Exception {
        mockMvc.perform(get("/api/v1/reports/tag-difficulty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(30)))
                .andExpect(jsonPath("$[*].problemCount", everyItem(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("an untouched tag reports null rates rather than a misleading zero")
    void untouchedTagsHaveNullRates() throws Exception {
        JsonNode rows = report();

        List<JsonNode> untouched = new ArrayList<>();
        rows.forEach(row -> {
            if (row.get("totalSubmissions").asLong() == 0) {
                untouched.add(row);
            }
        });

        assertThat(untouched)
                .as("the seed data leaves several advanced tags unattempted")
                .isNotEmpty();
        assertThat(untouched).allSatisfy(row -> {
            assertThat(row.has("acceptanceRate")).isFalse();
            assertThat(row.has("avgAttemptsToSolve")).isFalse();
            assertThat(row.get("distinctSolvers").asLong()).isZero();
        });
    }

    @Test
    @DisplayName("acceptance rate is accepted / total for tags that have been attempted")
    void acceptanceRateIsConsistent() throws Exception {
        JsonNode rows = report();

        rows.forEach(row -> {
            long total = row.get("totalSubmissions").asLong();
            long accepted = row.get("acceptedSubmissions").asLong();
            if (total > 0) {
                assertThat(row.get("acceptanceRate").asDouble())
                        .as("acceptance rate for tag %s", row.get("tag").asText())
                        .isCloseTo((double) accepted / total, org.assertj.core.data.Offset.offset(1e-9));
            }
            assertThat(accepted).isLessThanOrEqualTo(total);
        });
    }

    @Test
    @DisplayName("dp shows the low acceptance rate the seeded history implies")
    void dpIsHard() throws Exception {
        JsonNode rows = report();

        JsonNode dp = null;
        for (JsonNode row : rows) {
            if ("dp".equals(row.get("tag").asText())) {
                dp = row;
            }
        }

        assertThat(dp).isNotNull();
        assertThat(dp.get("totalSubmissions").asLong()).isPositive();
        assertThat(dp.get("acceptanceRate").asDouble()).isLessThan(0.5);
        assertThat(dp.get("distinctSolvers").asLong()).isPositive();
    }

    @Test
    @DisplayName("HARDEST ordering puts the lowest acceptance rate first and untouched tags last")
    void hardestOrdering() throws Exception {
        JsonNode rows = report("sort", "HARDEST");

        List<Double> rates = new ArrayList<>();
        boolean seenNull = false;
        for (JsonNode row : rows) {
            if (row.has("acceptanceRate")) {
                assertThat(seenNull)
                        .as("a tag with a rate must not appear after an untouched one")
                        .isFalse();
                rates.add(row.get("acceptanceRate").asDouble());
            } else {
                seenNull = true;
            }
        }

        assertThat(rates).isSorted();
        assertThat(rates).isNotEmpty();
    }

    @Test
    @DisplayName("minProblems filters out thinly covered tags")
    void minProblemsFilters() throws Exception {
        JsonNode all = report();
        JsonNode filtered = report("minProblems", "3");

        assertThat(filtered.size()).isLessThan(all.size());
        filtered.forEach(row -> assertThat(row.get("problemCount").asInt()).isGreaterThanOrEqualTo(3));
    }

    @Test
    @DisplayName("an unknown sort value is a 400 rather than reaching the SQL")
    void unknownSortIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/reports/tag-difficulty").param("sort", "DROP TABLE users"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/malformed-request"));
    }

    @Test
    @DisplayName("a negative minProblems is rejected by validation")
    void negativeMinProblemsIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/reports/tag-difficulty").param("minProblems", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("minProblems"));
    }
}
