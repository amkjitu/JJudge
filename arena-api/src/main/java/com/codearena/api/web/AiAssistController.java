package com.codearena.api.web;

import com.codearena.api.ai.AiClient;
import com.codearena.api.ai.dto.ComplexityView;
import com.codearena.api.ai.dto.HintView;
import com.codearena.api.service.CurrentUserProvider;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hints and complexity analysis, proxied to arena-ai.
 *
 * <h2>Why arena-api fronts this at all</h2>
 *
 * <p>arena-ai has no authentication and is not published. Everything a caller is allowed to ask
 * for is decided here, where there is already a user: this endpoint requires a login, and the
 * complexity endpoint additionally refuses to analyse a submission belonging to somebody else.
 * Exposing arena-ai directly would mean reimplementing all of that in a second service.
 *
 * <p>It also means the browser never learns arena-ai exists, so the AI provider can be swapped,
 * scaled or turned off without touching a single page.
 */
@RestController
@RequestMapping("/api/v1/assist")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Assist", description = "Hints and complexity analysis (authenticated)")
public class AiAssistController {

    private final ObjectProvider<AiClient> aiClient;
    private final ProblemService problemService;
    private final SubmissionService submissionService;
    private final CurrentUserProvider currentUserProvider;

    public AiAssistController(ObjectProvider<AiClient> aiClient,
                              ProblemService problemService,
                              SubmissionService submissionService,
                              CurrentUserProvider currentUserProvider) {
        this.aiClient = aiClient;
        this.problemService = problemService;
        this.submissionService = submissionService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/problems/{slug}/hint")
    @Operation(summary = "A hint for a problem",
            description = """
                    Level 1 is a question about how to approach the problem, level 3 may name the
                    technique. Never returns code.

                    `source` says whether a language model answered or the built-in hint library
                    did, so the caller can present it honestly.

                    Returns 503 when the AI service is unreachable. A hint is an extra: the rest
                    of the platform does not depend on it, and neither should the caller.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A hint at the requested level"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such problem", content = @Content),
            @ApiResponse(responseCode = "503", description = "AI service unavailable", content = @Content)
    })
    public ResponseEntity<HintView> hint(@PathVariable String slug,
                                         @RequestParam(defaultValue = "1") @Min(1) @Max(3) int level) {

        ProblemDetailResponse problem = problemService.getDetail(slug);

        AiClient client = aiClient.getIfAvailable();
        if (client == null) {
            return ResponseEntity.status(503).build();
        }

        return client.hint(problem.title(), problem.tags(), problem.rating(), level, null)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(503).build());
    }

    @GetMapping("/submissions/{id}/complexity")
    @Operation(summary = "Estimate the complexity of a submission",
            description = """
                    Analyses the stored source of one submission. Only the submission's own
                    author may ask - the source is theirs, and so is the analysis of it.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "An estimate, from a model or static analysis"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "403", description = "Not the submitter", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such submission, or its source is gone",
                    content = @Content),
            @ApiResponse(responseCode = "503", description = "AI service unavailable", content = @Content)
    })
    public ResponseEntity<ComplexityView> complexity(@PathVariable Long id) {
        var submission = submissionService.getById(id);

        // Checked explicitly, because getById does not. It is a public read by design - anyone
        // may see that a submission exists and what verdict it got, which is what the problem
        // page shows. The source behind it is not public, and neither is an analysis of it, so
        // the check belongs at every endpoint that reads the source rather than inside a lookup
        // that other callers rely on being open.
        if (!submission.username().equals(currentUserProvider.currentUsername())) {
            throw new AccessDeniedException("Submission " + id + " belongs to another user");
        }

        String sourceCode = submissionService.getSourceCode(id)
                .orElseThrow(() -> new ResourceNotFoundException("Submission source", id));

        AiClient client = aiClient.getIfAvailable();
        if (client == null) {
            return ResponseEntity.status(503).build();
        }

        return client.complexity(submission.language().name(), sourceCode)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(503).build());
    }
}
