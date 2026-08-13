package com.codearena.api.ui;

import com.codearena.api.service.SubmissionService;
import com.codearena.api.sse.SubmissionStream;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.common.domain.JudgingMethod;
import com.codearena.common.domain.Language;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import org.junit.jupiter.api.DisplayName;
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
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest({SubmissionUiController.class, ErrorPageUiController.class})
// The real SubmissionStream: it is an in-memory emitter registry with no collaborators,
// and the slice does not scan @Components.
@Import({UiSliceSecurityConfig.class, SubmissionStream.class})
@DisplayName("Submission pages")
class SubmissionUiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubmissionService submissionService;

    private static SubmissionResponse submissionOwnedBy(String username) {
        return new SubmissionResponse(81L, 23L, "edit-distance", "Edit Distance", username,
                Language.JAVA, SubmissionStatus.DONE, Verdict.AC, 145,
                JudgingMethod.EXECUTED, Instant.parse("2026-08-01T12:00:00Z"));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("history renders newest-first with the problem title")
    void historyRenders() throws Exception {
        when(submissionService.findByUsername(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(submissionOwnedBy("bob")), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/submissions"))
                .andExpect(status().isOk())
                .andExpect(view().name("submissions/list"))
                .andExpect(content().string(containsString("Edit Distance")))
                .andExpect(content().string(containsString("AC")));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("an empty history explains what to do next rather than showing a bare table")
    void emptyHistoryHasAnEmptyState() throws Exception {
        when(submissionService.findByUsername(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/submissions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pick a problem")));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("the owner sees their source code")
    void ownerSeesSource() throws Exception {
        when(submissionService.getById(81L)).thenReturn(submissionOwnedBy("bob"));
        when(submissionService.getSourceCode(81L)).thenReturn(Optional.of("class Main {}"));

        mockMvc.perform(get("/submissions/81"))
                .andExpect(status().isOk())
                .andExpect(view().name("submissions/detail"))
                .andExpect(content().string(containsString("class Main {}")));
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("someone else's submission is a 403 page, not a 500")
    void otherUsersSubmissionIsForbidden() throws Exception {
        // Regression guard. The controller throws AccessDeniedException, and the advice's
        // catch-all Exception handler used to swallow it and report a 500 - turning "not
        // yours" into "the server broke", and logging it at ERROR on every probe.
        when(submissionService.getById(1L)).thenReturn(submissionOwnedBy("carol"));

        mockMvc.perform(get("/submissions/1"))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/generic"))
                .andExpect(content().string(containsString("Not allowed")));

        verify(submissionService, never()).getSourceCode(1L);
    }

    @Test
    @WithMockUser(username = "bob")
    @DisplayName("source code is escaped, never rendered as markup")
    void sourceIsEscaped() throws Exception {
        when(submissionService.getById(81L)).thenReturn(submissionOwnedBy("bob"));
        when(submissionService.getSourceCode(81L))
                .thenReturn(Optional.of("<script>alert('xss')</script>"));

        mockMvc.perform(get("/submissions/81"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    @DisplayName("the 403 page is mapped for POST, not only GET")
    void forbiddenPageAcceptsPost() throws Exception {
        // Spring Security forwards to accessDeniedPage preserving the request method, so a
        // CSRF failure on a form POST arrives at this handler as a POST. Mapped GET-only, it
        // produced a 405 with Boot's default JSON body instead of the 403 page.
        //
        // The token is supplied because MockMvc issues a real REQUEST dispatch here, whereas
        // the production path is a FORWARD, which filters do not re-apply. What is under test
        // is the handler mapping accepting POST at all.
        mockMvc.perform(post("/error/403").with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/generic"));
    }
}
