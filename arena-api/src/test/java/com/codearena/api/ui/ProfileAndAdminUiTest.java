package com.codearena.api.ui;

import com.codearena.api.service.LeaderboardService;
import com.codearena.api.service.ProblemAuthoringService;
import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.TagService;
import com.codearena.api.service.UserService;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.ProblemSummaryResponse;
import com.codearena.api.web.dto.ProgressPointResponse;
import com.codearena.api.web.dto.TagResponse;
import com.codearena.api.web.dto.UserProfileResponse;
import com.codearena.api.web.dto.UserTagStatResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.common.domain.Difficulty;
import com.codearena.common.domain.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest({UserUiController.class, LeaderboardUiController.class, AdminUiController.class,
        ErrorPageUiController.class})
@Import(UiSliceSecurityConfig.class)
@DisplayName("Profile, leaderboard and admin pages")
class ProfileAndAdminUiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private ProblemService problemService;

    @MockBean
    private TagService tagService;

    @MockBean
    private LeaderboardService leaderboardService;

    @MockBean
    private ProblemAuthoringService authoringService;

    private static UserProfileResponse profile() {
        return new UserProfileResponse(3L, "bob", Role.USER, 1450,
                Instant.parse("2025-05-01T00:00:00Z"), 14, 22,
                List.of(new UserTagStatResponse("dp", 1, 4, 0.1428),
                        new UserTagStatResponse("arrays", 5, 5, 0.625)));
    }

    @Nested
    @DisplayName("profile")
    class Profile {

        @Test
        @DisplayName("renders the stat cards and the per-topic table")
        void rendersProfile() throws Exception {
            when(userService.getProfile("bob")).thenReturn(profile());
            when(userService.progress("bob")).thenReturn(
                    List.of(new ProgressPointResponse("2026-01", 5),
                            new ProgressPointResponse("2026-02", 14)));

            mockMvc.perform(get("/users/bob"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("users/profile"))
                    .andExpect(content().string(containsString("1450")))
                    .andExpect(content().string(containsString("Weakest topic")))
                    // dp is first in tagStats, so it is the weakest
                    .andExpect(content().string(containsString("dp")))
                    .andExpect(content().string(containsString("id=\"tagChart\"")))
                    .andExpect(content().string(containsString("id=\"progressChart\"")));
        }

        @Test
        @DisplayName("chart data is emitted as JSON, not built up in the template")
        void chartDataIsSerialisedJson() throws Exception {
            when(userService.getProfile("bob")).thenReturn(profile());
            when(userService.progress("bob"))
                    .thenReturn(List.of(new ProgressPointResponse("2026-02", 14)));

            mockMvc.perform(get("/users/bob"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("window.arenaChartData")))
                    .andExpect(content().string(containsString("cumulativeSolved")));
        }

        @Test
        @DisplayName("a profile whose owner has solved nothing still renders")
        void emptyProfileRenders() throws Exception {
            when(userService.getProfile("newcomer")).thenReturn(new UserProfileResponse(
                    9L, "newcomer", Role.USER, 1200, Instant.now(), 0, 0, List.of()));
            when(userService.progress("newcomer")).thenReturn(List.of());

            mockMvc.perform(get("/users/newcomer"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("No attempts recorded yet")));
        }

        @Test
        @DisplayName("an unknown user renders the HTML error page, not a JSON problem document")
        void unknownUserRendersHtml() throws Exception {
            when(userService.getProfile("nobody"))
                    .thenThrow(new ResourceNotFoundException("User", "nobody"));

            mockMvc.perform(get("/users/nobody"))
                    .andExpect(status().isNotFound())
                    .andExpect(view().name("error/generic"))
                    .andExpect(content().contentTypeCompatibleWith("text/html"))
                    .andExpect(content().string(not(containsString("\"type\":\"https://codearena.dev"))));
        }

        @Test
        @WithMockUser(username = "carol")
        @DisplayName("/me redirects to the caller's own profile")
        void meRedirects() throws Exception {
            mockMvc.perform(get("/me"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/users/carol"));
        }
    }

    @Nested
    @DisplayName("admin")
    class Admin {

        @BeforeEach
        void stubTags() {
            when(tagService.findAllWithPrerequisites())
                    .thenReturn(List.of(new TagResponse(1L, "dp", Set.of())));
            // The list shows each problem's authoring state; without this the column is empty
            // rather than absent, and the page still renders.
            when(authoringService.statusOf(anyList())).thenReturn(Map.of("two-sum",
                    new ProblemAuthoringService.AuthoringStatus(true, 9, 2, List.of())));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("the problem list offers edit and delete")
        void adminListRenders() throws Exception {
            when(problemService.search(any(ProblemFilter.class), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(new ProblemSummaryResponse(1L, "Two Sum",
                            "two-sum", Difficulty.EASY, 800, Set.of("arrays"))),
                            PageRequest.of(0, 20), 1));

            mockMvc.perform(get("/admin/problems"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/problems"))
                    .andExpect(content().string(containsString("two-sum")))
                    .andExpect(content().string(containsString("Edit")))
                    // delete is a POST form, never a link a crawler could follow
                    .andExpect(content().string(containsString("/admin/problems/two-sum/delete")));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("the create form offers no difficulty input - it is derived from rating")
        void createFormHasNoDifficultyField() throws Exception {
            mockMvc.perform(get("/admin/problems/new"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/problem-form"))
                    .andExpect(content().string(containsString("id=\"rating\"")))
                    .andExpect(content().string(containsString("Difficulty is derived from this")))
                    .andExpect(content().string(not(containsString("id=\"difficulty\""))));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("the edit form locks the slug")
        void editFormLocksSlug() throws Exception {
            when(problemService.getDetail("two-sum")).thenReturn(new ProblemDetailResponse(
                    1L, "Two Sum", "two-sum", Difficulty.EASY, 800, 1000, 256,
                    Set.of("arrays"), Instant.now(), null));

            mockMvc.perform(get("/admin/problems/two-sum/edit"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("readonly")))
                    .andExpect(content().string(containsString("cannot be changed after creation")));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("an unknown tag is reported on the tags field")
        void unknownTagIsAFieldError() throws Exception {
            when(problemService.create(any()))
                    .thenThrow(new IllegalArgumentException("Unknown tags: quantum"));

            mockMvc.perform(post("/admin/problems")
                            .with(csrf())
                            .param("title", "Test")
                            .param("slug", "test-problem")
                            .param("rating", "1500")
                            .param("tags", "quantum"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("admin/problem-form"))
                    .andExpect(content().string(containsString("Unknown tags: quantum")));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("a valid create redirects back to the list")
        void createRedirects() throws Exception {
            when(problemService.create(any())).thenReturn(new ProblemDetailResponse(
                    2L, "Test", "test-problem", Difficulty.MEDIUM, 1500, 1000, 256,
                    Set.of("dp"), Instant.now(), null));

            mockMvc.perform(post("/admin/problems")
                            .with(csrf())
                            .param("title", "Test")
                            .param("slug", "test-problem")
                            .param("rating", "1500")
                            .param("tags", "dp"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/problems"));
        }

        @Test
        @WithMockUser(username = "admin", roles = "ADMIN")
        @DisplayName("delete goes through the service and redirects")
        void deleteRedirects() throws Exception {
            mockMvc.perform(post("/admin/problems/two-sum/delete").with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/problems"));

            verify(problemService).delete("two-sum");
        }
    }

    @Test
    @DisplayName("leaderboard renders the ranked table")
    void leaderboardRenders() throws Exception {
        when(leaderboardService.top(50)).thenReturn(List.of(
                new com.codearena.api.web.dto.LeaderboardEntryResponse(1, "carol", 1750, 25)));

        mockMvc.perform(get("/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(view().name("leaderboard"))
                .andExpect(content().string(containsString("carol")))
                .andExpect(content().string(containsString("1750")));
    }

    @Test
    @DisplayName("the 403 page keeps its status rather than redirecting")
    void forbiddenPageKeepsStatus() throws Exception {
        mockMvc.perform(get("/error/403"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/generic"))
                .andExpect(content().string(containsString("Not allowed")));
    }
}
