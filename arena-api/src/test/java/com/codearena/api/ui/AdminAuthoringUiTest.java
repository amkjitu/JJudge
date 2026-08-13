package com.codearena.api.ui;

import com.codearena.api.mongo.ProblemStatement;
import com.codearena.api.mongo.ProblemTestCases;
import com.codearena.api.service.ProblemAuthoringService;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.TagService;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.TagResponse;
import com.codearena.common.domain.Difficulty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * The admin editors for a problem's statement and test cases.
 *
 * <p>These pages are what makes a problem judgeable, and before they existed the only way to add
 * a problem the judge could actually run was to edit a JSON file in the repository and restart.
 * A problem created through the UI got a title, a rating and a simulated verdict.
 */
@WebMvcTest(AdminUiController.class)
@Import({UiSliceSecurityConfig.class, MethodSecurityTestConfig.class})
@DisplayName("Admin authoring pages")
class AdminAuthoringUiTest {


    private static final String SLUG = "two-sum";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProblemService problemService;

    @MockBean
    private TagService tagService;

    @MockBean
    private ProblemAuthoringService authoringService;

    @BeforeEach
    void stubProblem() {
        when(tagService.findAllWithPrerequisites())
                .thenReturn(List.of(new TagResponse(1L, "arrays", Set.of())));
        when(problemService.getDetail(SLUG)).thenReturn(new ProblemDetailResponse(
                1L, "Two Sum", SLUG, Difficulty.EASY, 800, 1000, 256,
                Set.of("arrays"), Instant.now(), null));
        when(authoringService.statusOf(anyString())).thenReturn(complete());
    }

    private static ProblemAuthoringService.AuthoringStatus complete() {
        return new ProblemAuthoringService.AuthoringStatus(true, 9, 2, List.of());
    }

    @Nested
    @DisplayName("the statement editor")
    class Statement {

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("offers a statement, an editorial and worked examples")
        void rendersFields() throws Exception {
            when(authoringService.findStatement(SLUG)).thenReturn(Optional.of(
                    new ProblemStatement(SLUG, "Find two numbers.", "Use a hash map.",
                            List.of(new ProblemStatement.WorkedExample("4 9", "0 1", "2+7=9")))));

            mockMvc.perform(get("/admin/problems/{slug}/statement", SLUG))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/problem-statement"))
                    .andExpect(content().string(containsString("Find two numbers.")))
                    .andExpect(content().string(containsString("Use a hash map.")))
                    .andExpect(content().string(containsString("examples[0].input")))
                    .andExpect(content().string(containsString("0 1")));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("renders an empty row for a problem that has no statement yet")
        void rendersBlankRowWhenAbsent() throws Exception {
            // Without a seeded row there is nothing to type into and nothing for the "add"
            // button to clone, which reads as a broken page rather than an empty one.
            when(authoringService.findStatement(SLUG)).thenReturn(Optional.empty());

            mockMvc.perform(get("/admin/problems/{slug}/statement", SLUG))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("examples[0].input")));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("saves the statement and drops rows left blank")
        void savesAndDropsBlankRows() throws Exception {
            mockMvc.perform(post("/admin/problems/{slug}/statement", SLUG).with(csrf())
                            .param("statementMarkdown", "Find two numbers.")
                            .param("editorialMarkdown", "")
                            .param("examples[0].input", "4 9")
                            .param("examples[0].output", "0 1")
                            .param("examples[0].explanation", "")
                            .param("examples[1].input", "")
                            .param("examples[1].output", "")
                            .param("examples[1].explanation", ""))
                    .andExpect(redirectedUrl("/admin/problems/two-sum/statement"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ProblemStatement.WorkedExample>> examples =
                    ArgumentCaptor.forClass(List.class);
            verify(authoringService).saveStatement(eq(SLUG), eq("Find two numbers."),
                    anyString(), examples.capture());

            assertThat(examples.getValue()).singleElement()
                    .satisfies(e -> {
                        assertThat(e.input()).isEqualTo("4 9");
                        assertThat(e.output()).isEqualTo("0 1");
                        // An empty explanation is stored as null, not as "".
                        assertThat(e.explanation()).isNull();
                    });
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("refuses a half-filled example rather than storing one with no output")
        void halfFilledExampleIsRejected() throws Exception {
            mockMvc.perform(post("/admin/problems/{slug}/statement", SLUG).with(csrf())
                            .param("statementMarkdown", "Find two numbers.")
                            .param("examples[0].input", "4 9")
                            .param("examples[0].output", "")
                            .param("examples[0].explanation", ""))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/problem-statement"))
                    .andExpect(content().string(containsString("needs the output it produces")));

            verify(authoringService, never()).saveStatement(anyString(), anyString(),
                    anyString(), anyList());
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("refuses an empty statement")
        void emptyStatementIsRejected() throws Exception {
            mockMvc.perform(post("/admin/problems/{slug}/statement", SLUG).with(csrf())
                            .param("statementMarkdown", ""))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("a problem needs a statement")));

            verify(authoringService, never()).saveStatement(anyString(), anyString(),
                    anyString(), anyList());
        }
    }

    @Nested
    @DisplayName("the test-case editor")
    class TestCases {

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("shows every case with its input, expected output and sample flag")
        void rendersCases() throws Exception {
            when(authoringService.findTestCases(SLUG)).thenReturn(Optional.of(
                    new ProblemTestCases(SLUG, List.of(
                            new ProblemTestCases.Case(1, "4 9\n2 7 11 15", "0 1", true),
                            new ProblemTestCases.Case(2, "3 6\n3 2 4", "1 2", false)))));

            mockMvc.perform(get("/admin/problems/{slug}/test-cases", SLUG))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/problem-test-cases"))
                    .andExpect(content().string(containsString("cases[0].input")))
                    .andExpect(content().string(containsString("cases[1].expectedOutput")))
                    .andExpect(content().string(containsString("2 7 11 15")));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("renumbers cases from row order, so an inserted case leaves no gap")
        void indicesComeFromRowOrder() throws Exception {
            mockMvc.perform(post("/admin/problems/{slug}/test-cases", SLUG).with(csrf())
                            .param("cases[0].input", "a").param("cases[0].expectedOutput", "1")
                            .param("cases[0].sample", "true")
                            // A blank row between two filled ones - the case that would leave a
                            // hole if indices were taken from the submitted position.
                            .param("cases[1].input", "").param("cases[1].expectedOutput", "")
                            .param("cases[2].input", "b").param("cases[2].expectedOutput", "2"))
                    .andExpect(redirectedUrl("/admin/problems/two-sum/test-cases"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ProblemTestCases.Case>> saved = ArgumentCaptor.forClass(List.class);
            verify(authoringService).saveTestCases(eq(SLUG), saved.capture());

            assertThat(saved.getValue()).extracting(ProblemTestCases.Case::index)
                    .containsExactly(1, 2);
            assertThat(saved.getValue()).extracting(ProblemTestCases.Case::input)
                    .containsExactly("a", "b");
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("keeps a case whose expected output is legitimately empty")
        void emptyExpectedOutputIsAllowed() throws Exception {
            // A program can be correct and print nothing. Rejecting this would make such a
            // problem impossible to author.
            mockMvc.perform(post("/admin/problems/{slug}/test-cases", SLUG).with(csrf())
                            .param("cases[0].input", "0")
                            .param("cases[0].expectedOutput", "")
                            .param("cases[0].sample", "true"))
                    .andExpect(redirectedUrl("/admin/problems/two-sum/test-cases"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ProblemTestCases.Case>> saved = ArgumentCaptor.forClass(List.class);
            verify(authoringService).saveTestCases(eq(SLUG), saved.capture());
            assertThat(saved.getValue()).singleElement()
                    .extracting(ProblemTestCases.Case::expectedOutput).isEqualTo("");
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("insists on at least one sample, since samples are what the reader sees")
        void requiresASample() throws Exception {
            mockMvc.perform(post("/admin/problems/{slug}/test-cases", SLUG).with(csrf())
                            .param("cases[0].input", "a")
                            .param("cases[0].expectedOutput", "1"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Mark at least one case as a sample")));

            verify(authoringService, never()).saveTestCases(anyString(), anyList());
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("saving no cases is allowed, and says the problem becomes simulated")
        void clearingCasesIsAllowedButAnnounced() throws Exception {
            mockMvc.perform(post("/admin/problems/{slug}/test-cases", SLUG).with(csrf())
                            .param("cases[0].input", "")
                            .param("cases[0].expectedOutput", ""))
                    .andExpect(redirectedUrl("/admin/problems/two-sum/test-cases"));

            verify(authoringService).saveTestCases(SLUG, List.of());
        }
    }

    @Nested
    @DisplayName("authoring status")
    class Status {

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("warns when a problem has no test cases and is therefore simulated")
        void warnsAboutSimulatedProblems() throws Exception {
            when(authoringService.statusOf(SLUG)).thenReturn(
                    new ProblemAuthoringService.AuthoringStatus(true, 0, 0, List.of()));
            when(authoringService.findTestCases(SLUG)).thenReturn(Optional.empty());

            mockMvc.perform(get("/admin/problems/{slug}/test-cases", SLUG))
                    .andExpect(content().string(containsString("No test cases")))
                    .andExpect(content().string(containsString("Simulated")));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("reports a statement example that no sample case actually runs")
        void reportsDrift() throws Exception {
            // The failure this exists to prevent: a reader is shown one thing and their code is
            // marked against another, which looks like their own mistake.
            when(authoringService.statusOf(SLUG)).thenReturn(
                    new ProblemAuthoringService.AuthoringStatus(true, 9, 2,
                            List.of("an example shown in the statement is not among the sample "
                                    + "cases, so it is displayed but never run")));
            when(authoringService.findStatement(SLUG)).thenReturn(Optional.empty());

            mockMvc.perform(get("/admin/problems/{slug}/statement", SLUG))
                    .andExpect(content().string(
                            containsString("The statement and the test cases disagree")))
                    .andExpect(content().string(containsString("displayed but never run")));
        }
    }

    @Nested
    @DisplayName("access")
    class Access {

        @Test
        @WithMockUser(username = "bob", roles = "USER")
        @DisplayName("an ordinary user cannot reach the editors")
        void nonAdminIsRefused() throws Exception {
            mockMvc.perform(get("/admin/problems/{slug}/statement", SLUG))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/admin/problems/{slug}/test-cases", SLUG))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "bob", roles = "USER")
        @DisplayName("an ordinary user cannot write test cases")
        void nonAdminCannotSave() throws Exception {
            mockMvc.perform(post("/admin/problems/{slug}/test-cases", SLUG).with(csrf())
                            .param("cases[0].input", "a")
                            .param("cases[0].expectedOutput", "1")
                            .param("cases[0].sample", "true"))
                    .andExpect(status().isForbidden());

            verify(authoringService, never()).saveTestCases(anyString(), anyList());
        }
    }
}
