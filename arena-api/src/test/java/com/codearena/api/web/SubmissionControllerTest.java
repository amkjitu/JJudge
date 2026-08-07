package com.codearena.api.web;

import com.codearena.api.service.CurrentUserProvider;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.web.dto.CreateSubmissionRequest;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.common.domain.Language;
import com.codearena.common.domain.SubmissionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice with security filters switched off on purpose: {@code @WebMvcTest} does not
 * scan {@code SecurityConfig}, so leaving them on would apply Boot's default "authenticate
 * everything with HTTP Basic" chain - which tests neither the real rules nor the controller.
 * The genuine authorization matrix lives in {@code AuthorizationApiIT}, against the real chain.
 */
@WebMvcTest(SubmissionController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("/api/v1/submissions")
class SubmissionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SubmissionService submissionService;

    @MockBean
    private CurrentUserProvider currentUserProvider;

    @BeforeEach
    void stubCurrentUser() {
        when(currentUserProvider.currentUsername()).thenReturn("bob");
    }

    private static SubmissionResponse queuedSubmission() {
        return new SubmissionResponse(101L, 10L, "maximum-subarray-sum", "Maximum Subarray Sum",
                "bob", Language.JAVA, SubmissionStatus.QUEUED, null, null,
                Instant.parse("2026-08-07T10:15:30Z"));
    }

    @Test
    @DisplayName("POST returns 201 with a Location header pointing at the new submission")
    void createReturns201WithLocation() throws Exception {
        when(submissionService.create(eq("bob"), any(CreateSubmissionRequest.class)))
                .thenReturn(queuedSubmission());

        String body = objectMapper.writeValueAsString(new CreateSubmissionRequest(
                "maximum-subarray-sum", Language.JAVA, "class Main {}"));

        mockMvc.perform(post("/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/submissions/101"))
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.problemSlug").value("maximum-subarray-sum"))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                // no verdict yet - and omitted rather than serialised as null
                .andExpect(jsonPath("$.verdict").doesNotExist())
                .andExpect(jsonPath("$.runtimeMs").doesNotExist());
    }

    @Test
    @DisplayName("the submitting user comes from the auth context, never from the body")
    void usernameIsNotTakenFromTheBody() throws Exception {
        when(submissionService.create(eq("bob"), any(CreateSubmissionRequest.class)))
                .thenReturn(queuedSubmission());

        // A caller trying to submit as somebody else: the extra field is ignored outright.
        String body = """
                {
                  "problemSlug": "maximum-subarray-sum",
                  "language": "JAVA",
                  "sourceCode": "class Main {}",
                  "username": "carol"
                }
                """;

        mockMvc.perform(post("/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(submissionService).create(eq("bob"), any(CreateSubmissionRequest.class));
    }

    @Test
    @DisplayName("a blank payload is a 400 listing every offending field")
    void validationFailureListsFields() throws Exception {
        String body = """
                { "problemSlug": "", "sourceCode": "" }
                """;

        mockMvc.perform(post("/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/validation-failed"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(org.hamcrest.Matchers.containsInAnyOrder(
                                "problemSlug", "language", "sourceCode")));

        verify(submissionService, never()).create(any(), any());
    }

    @Test
    @DisplayName("source code over 64 KiB is rejected")
    void oversizedSourceIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateSubmissionRequest(
                "maximum-subarray-sum", Language.JAVA, "x".repeat(65537)));

        mockMvc.perform(post("/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sourceCode"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value(org.hamcrest.Matchers.containsString("64 KiB")));
    }

    @Test
    @DisplayName("malformed JSON is a 400 that does not echo Jackson internals")
    void malformedJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/malformed-request"))
                .andExpect(jsonPath("$.detail").value("Request body is missing or not valid JSON"));
    }

    @Test
    @DisplayName("source is served as text/plain so it can be piped straight to a file")
    void sourceIsPlainText() throws Exception {
        when(submissionService.getSourceCode(101L)).thenReturn(Optional.of("class Main {}"));

        mockMvc.perform(get("/api/v1/submissions/101/source"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("class Main {}"));
    }

    @Test
    @DisplayName("a submission whose source has aged out of memory returns 404, not empty 200")
    void missingSourceIs404() throws Exception {
        when(submissionService.getSourceCode(101L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/submissions/101/source"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.resourceType").value("Submission source"));
    }

    @Test
    @DisplayName("a non-numeric id is a 400 rather than a 500")
    void nonNumericIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/submissions/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("could not be converted")));
    }

    @Test
    @DisplayName("an unknown submission id is a 404 problem detail")
    void unknownSubmissionIs404() throws Exception {
        when(submissionService.getById(999L))
                .thenThrow(new ResourceNotFoundException("Submission", 999L));

        mockMvc.perform(get("/api/v1/submissions/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.identifier").value("999"));
    }
}
