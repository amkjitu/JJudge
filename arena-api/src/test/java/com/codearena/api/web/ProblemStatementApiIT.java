package com.codearena.api.web;

import com.codearena.api.mongo.ProblemStatementRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Problem prose from MongoDB, all the way out to the API and the page.
 *
 * <p>The interesting part is the join across two stores: PostgreSQL owns the record, MongoDB
 * owns the text, and the detail view has to produce one coherent answer whether or not the
 * second half exists.
 */
@DisplayName("Problem statements from MongoDB")
class ProblemStatementApiIT extends AbstractApiIT {

    /** Seeded in {@code mongo/problem-statements.json}. */
    private static final String WITH_STATEMENT = "maximum-subarray-sum";

    /** A real seeded problem that the bundled statement file deliberately does not cover. */
    private static final String WITHOUT_STATEMENT = "rotate-matrix-in-place";

    @Autowired
    private ProblemStatementRepository statements;

    @Test
    @DisplayName("the seeder loaded the bundled statements")
    void seederRan() {
        assertThat(statements.findById(WITH_STATEMENT)).isPresent();
    }

    @Test
    @DisplayName("seeding twice leaves one document per slug")
    void seedingIsIdempotent() {
        // The seeder upserts on every start rather than guarding itself with a "already ran"
        // flag, so restarts must not accumulate duplicates. The slug being the _id is what
        // guarantees it, and this is the assertion that would catch a change to that key.
        long before = statements.count();

        statements.save(statements.findById(WITH_STATEMENT).orElseThrow());

        assertThat(statements.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("the detail endpoint carries the Markdown statement")
    void apiReturnsTheStatement() throws Exception {
        mockMvc.perform(get("/api/v1/problems/{slug}", WITH_STATEMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(WITH_STATEMENT))
                .andExpect(jsonPath("$.statementMarkdown").exists())
                .andExpect(jsonPath("$.statementMarkdown").value(
                        org.hamcrest.Matchers.containsString("contiguous subarray")));
    }

    @Test
    @DisplayName("a problem with no statement is still returned in full")
    void apiWithoutAStatement() throws Exception {
        mockMvc.perform(get("/api/v1/problems/{slug}", WITHOUT_STATEMENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value(WITHOUT_STATEMENT))
                .andExpect(jsonPath("$.rating").exists())
                // Omitted rather than serialised as an explicit null - see the Jackson config.
                .andExpect(jsonPath("$.statementMarkdown").doesNotExist());
    }

    @Test
    @DisplayName("the detail page renders the statement as HTML, not raw Markdown")
    void pageRendersMarkdown() throws Exception {
        mockMvc.perform(get("/problems/{slug}", WITH_STATEMENT))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("<h2>Constraints</h2>")))
                // The worked examples come from the same document.
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("Examples")));
    }

    @Test
    @DisplayName("the editorial is on the page but collapsed behind a spoiler control")
    void editorialIsHiddenByDefault() throws Exception {
        String html = mockMvc.perform(get("/problems/{slug}", WITH_STATEMENT))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Show editorial");
        // Present in the DOM but inside a collapsed container: handing someone Kadane's algorithm
        // beside the submit box defeats the point of the exercise.
        assertThat(html).contains("id=\"editorial\"");
        assertThat(html).contains("class=\"collapse");
    }
}
