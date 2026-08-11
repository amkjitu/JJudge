package com.codearena.api.ui;

import com.codearena.api.ai.AiClient;
import com.codearena.api.ai.dto.HintView;
import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.MarkdownRenderer;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.ProblemStatementService;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.service.TagService;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.ProblemSummaryResponse;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.api.web.dto.TagResponse;
import com.codearena.common.domain.Difficulty;
import com.codearena.common.domain.Language;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Renders the problem pages for real - Thymeleaf runs, fragments resolve, and the assertions
 * read the produced HTML. A template typo that a controller test on model attributes alone
 * would miss fails here.
 */
@WebMvcTest(ProblemUiController.class)
// The real MarkdownRenderer rather than a mock: it is a pure function with no collaborators, so
// stubbing it would only guarantee that the template's th:utext never sees real rendered HTML.
@Import({UiSliceSecurityConfig.class, MarkdownRenderer.class})
@DisplayName("Problem pages")
class ProblemUiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProblemService problemService;

    @MockBean
    private SubmissionService submissionService;

    @MockBean
    private TagService tagService;

    @MockBean
    private ProblemStatementService statementService;

    @MockBean
    private AiClient aiClient;

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

    @BeforeEach
    void stubTags() {
        when(tagService.findAllWithPrerequisites())
                .thenReturn(List.of(new TagResponse(1L, "dp", Set.of()),
                        new TagResponse(2L, "graph", Set.of())));
    }

    @Test
    @DisplayName("list renders the catalogue table")
    void listRenders() throws Exception {
        when(problemService.search(any(ProblemFilter.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/problems"))
                .andExpect(status().isOk())
                .andExpect(view().name("problems/list"))
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dijkstra on a Weighted Grid")))
                // the nav fragment from the shared layout made it into the page
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CodeArena")));
    }

    @Test
    @DisplayName("filter values are echoed back into the form so a filtered URL is shareable")
    void filtersRoundTripIntoTheForm() throws Exception {
        when(problemService.search(any(ProblemFilter.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/problems")
                        .param("tag", "dp")
                        .param("difficulty", "HARD")
                        .param("search", "knapsack"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("filter",
                        new ProblemFilter("dp", Difficulty.HARD, null, null, "knapsack")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"knapsack\"")));

        ArgumentCaptor<ProblemFilter> filter = ArgumentCaptor.forClass(ProblemFilter.class);
        verify(problemService).search(filter.capture(), any(Pageable.class));
        assertThat(filter.getValue().tag()).isEqualTo("dp");
    }

    @Test
    @DisplayName("an anonymous visitor sees no solved ticks and no editor")
    void anonymousSeesNoEditor() throws Exception {
        when(problemService.getDetail("dijkstra-on-a-weighted-grid")).thenReturn(detail());

        mockMvc.perform(get("/problems/dijkstra-on-a-weighted-grid"))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("solvedIds", "mySubmissions"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("to submit a solution")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("id=\"submit-form\""))));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("a signed-in user gets the editor and their own attempts")
    void authenticatedSeesEditor() throws Exception {
        when(problemService.getDetail("dijkstra-on-a-weighted-grid")).thenReturn(detail());
        when(submissionService.findByUsernameAndProblem("bob", "dijkstra-on-a-weighted-grid"))
                .thenReturn(List.of(new SubmissionResponse(7L, 24L, "dijkstra-on-a-weighted-grid",
                        "Dijkstra on a Weighted Grid", "bob", Language.JAVA,
                        SubmissionStatus.DONE, Verdict.WA, 120, Instant.parse("2026-02-01T00:00:00Z"))));

        mockMvc.perform(get("/problems/dijkstra-on-a-weighted-grid"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"submit-form\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"sourceCode\"")))
                // the CSRF hidden input Thymeleaf adds to th:action forms
                .andExpect(content().string(org.hamcrest.Matchers.containsString("_csrf")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your attempts")));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("solved problems are ticked using one set lookup, not a query per row")
    void solvedTicksComeFromASingleSet() throws Exception {
        when(problemService.search(any(ProblemFilter.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(summary()), PageRequest.of(0, 20), 1));
        when(submissionService.solvedProblemIds("bob")).thenReturn(Set.of(24L));

        mockMvc.perform(get("/problems"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("solvedIds", Set.of(24L)));

        verify(submissionService).solvedProblemIds("bob");
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("a valid submission redirects to the new submission")
    void submitRedirects() throws Exception {
        when(submissionService.create(eq("bob"), any()))
                .thenReturn(new SubmissionResponse(101L, 24L, "dijkstra-on-a-weighted-grid",
                        "Dijkstra on a Weighted Grid", "bob", Language.JAVA,
                        SubmissionStatus.QUEUED, null, null, Instant.now()));

        mockMvc.perform(post("/problems/dijkstra-on-a-weighted-grid/submit")
                        .with(csrf())
                        .param("language", "JAVA")
                        .param("sourceCode", "class Main {}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/submissions/101"));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("a hint is returned as JSON with its level and its provenance")
    void hintReturnsJson() throws Exception {
        when(problemService.getDetail("dijkstra-on-a-weighted-grid")).thenReturn(detail());
        when(aiClient.hint(anyString(), any(), any(), eq(2), any()))
                .thenReturn(java.util.Optional.of(
                        new HintView("What does relaxing an edge tell you?", 2, 3, "HEURISTIC")));

        mockMvc.perform(get("/problems/dijkstra-on-a-weighted-grid/hint").param("level", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.hint").value("What does relaxing an edge tell you?"))
                .andExpect(jsonPath("$.level").value(2))
                // The UI shows this, so it has to survive the hop.
                .andExpect(jsonPath("$.source").value("HEURISTIC"));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("an unreachable AI service is a 503, not a broken page")
    void hintUnavailable() throws Exception {
        when(problemService.getDetail("dijkstra-on-a-weighted-grid")).thenReturn(detail());
        when(aiClient.hint(anyString(), any(), any(), any(Integer.class), any()))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/problems/dijkstra-on-a-weighted-grid/hint"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("empty source re-renders the page rather than redirecting, so nothing is lost")
    void emptySourceReRendersInPlace() throws Exception {
        when(problemService.getDetail(anyString())).thenReturn(detail());

        mockMvc.perform(post("/problems/dijkstra-on-a-weighted-grid/submit")
                        .with(csrf())
                        .param("language", "JAVA")
                        .param("sourceCode", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("problems/detail"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("write some code before submitting")));
    }
}
