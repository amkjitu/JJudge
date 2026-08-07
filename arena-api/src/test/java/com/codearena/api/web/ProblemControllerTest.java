package com.codearena.api.web;

import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.ProblemSummaryResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.common.domain.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice: real controller, real exception handler, mocked service. Verifies the HTTP
 * contract - status codes, JSON shape, error envelope - without a database.
 *
 * <p>Security filters are switched off on purpose. {@code @WebMvcTest} does not scan
 * {@code SecurityConfig}, so leaving them on would apply Boot's default "authenticate
 * everything with HTTP Basic" chain - which tests neither the real rules nor the controller.
 * The genuine authorization matrix lives in {@code AuthorizationApiIT}, against the real chain.
 */
@WebMvcTest(ProblemController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("GET /api/v1/problems")
class ProblemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProblemService problemService;

    @MockBean
    private SubmissionService submissionService;

    private static final Set<String> TAGS = new TreeSet<>(Set.of("graph", "heap", "shortest-path"));

    private static ProblemSummaryResponse summary() {
        return new ProblemSummaryResponse(24L, "Dijkstra on a Weighted Grid",
                "dijkstra-on-a-weighted-grid", Difficulty.MEDIUM, 1500, TAGS);
    }

    private static ProblemDetailResponse detail() {
        return new ProblemDetailResponse(24L, "Dijkstra on a Weighted Grid",
                "dijkstra-on-a-weighted-grid", Difficulty.MEDIUM, 1500, 3000, 512, TAGS,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    @DisplayName("returns the stable page envelope, not Spring's Page serialisation")
    void returnsPageEnvelope() throws Exception {
        Page<ProblemSummaryResponse> page = new PageImpl<>(List.of(summary()), PageRequest.of(0, 20), 1);
        when(problemService.search(any(ProblemFilter.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/problems"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].slug").value("dijkstra-on-a-weighted-grid"))
                .andExpect(jsonPath("$.content[0].difficulty").value("MEDIUM"))
                // tags are sorted so the payload is stable between requests
                .andExpect(jsonPath("$.content[0].tags[0]").value("graph"))
                .andExpect(jsonPath("$.content[0].tags[1]").value("heap"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.last").value(true))
                // Spring's own Page fields must not leak into the contract
                .andExpect(jsonPath("$.pageable").doesNotExist())
                .andExpect(jsonPath("$.sort").doesNotExist());
    }

    @Test
    @DisplayName("passes every filter through to the service")
    void forwardsFilters() throws Exception {
        when(problemService.search(any(ProblemFilter.class), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/problems")
                        .param("tag", "dp")
                        .param("difficulty", "HARD")
                        .param("minRating", "1200")
                        .param("maxRating", "1600")
                        .param("search", "knapsack"))
                .andExpect(status().isOk());

        ArgumentCaptor<ProblemFilter> filter = ArgumentCaptor.forClass(ProblemFilter.class);
        verify(problemService).search(filter.capture(), any(Pageable.class));
        assertThat(filter.getValue()).isEqualTo(
                new ProblemFilter("dp", Difficulty.HARD, 1200, 1600, "knapsack"));
    }

    @Test
    @DisplayName("summary listing omits the detail-only fields")
    void summaryOmitsDetailFields() throws Exception {
        when(problemService.search(any(ProblemFilter.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary())));

        mockMvc.perform(get("/api/v1/problems"))
                .andExpect(jsonPath("$.content[0].timeLimitMs").doesNotExist())
                .andExpect(jsonPath("$.content[0].memoryLimitMb").doesNotExist());
    }

    @Test
    @DisplayName("detail view includes limits and creation time")
    void detailIncludesLimits() throws Exception {
        when(problemService.getDetail("dijkstra-on-a-weighted-grid")).thenReturn(detail());

        mockMvc.perform(get("/api/v1/problems/dijkstra-on-a-weighted-grid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeLimitMs").value(3000))
                .andExpect(jsonPath("$.memoryLimitMb").value(512))
                // not populated until Phase 7, and omitted rather than serialised as null
                .andExpect(jsonPath("$.statementMarkdown").doesNotExist());
    }

    @Test
    @DisplayName("a missing problem returns an RFC 7807 body, not a bare 404")
    void notFoundIsProblemDetail() throws Exception {
        when(problemService.getDetail(anyString()))
                .thenThrow(new ResourceNotFoundException("Problem", "nope"));

        mockMvc.perform(get("/api/v1/problems/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/resource-not-found"))
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Problem 'nope' not found"))
                .andExpect(jsonPath("$.resourceType").value("Problem"))
                .andExpect(jsonPath("$.identifier").value("nope"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("an out-of-range rating filter is a 400 with field-level detail")
    void invalidRatingIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/problems").param("minRating", "99999"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("minRating"))
                .andExpect(jsonPath("$.errors[0].rejectedValue").value(99999));
    }

    @Test
    @DisplayName("an unparseable difficulty is a 400, not a 500")
    void unknownDifficultyIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/problems").param("difficulty", "IMPOSSIBLE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/malformed-request"))
                .andExpect(jsonPath("$.detail").value(containsString("difficulty")));
    }
}
