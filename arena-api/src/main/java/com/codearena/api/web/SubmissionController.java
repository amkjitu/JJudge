package com.codearena.api.web;

import com.codearena.api.service.CurrentUserProvider;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.web.dto.CreateSubmissionRequest;
import com.codearena.api.web.dto.PageResponse;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/submissions")
@Tag(name = "Submissions", description = "Submit solutions and read verdicts")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final CurrentUserProvider currentUserProvider;

    public SubmissionController(SubmissionService submissionService,
                                CurrentUserProvider currentUserProvider) {
        this.submissionService = submissionService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping
    @Operation(summary = "Submit a solution",
            description = """
                    Records the attempt and returns it as QUEUED. Judging is asynchronous and
                    lands in Phase 6; until then the submission stays QUEUED and carries no
                    verdict. The submitting user comes from the authentication context, never
                    from the request body.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Submission accepted and queued"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such problem", content = @Content)
    })
    public ResponseEntity<SubmissionResponse> submit(@Valid @RequestBody CreateSubmissionRequest request,
                                                     UriComponentsBuilder uriBuilder) {
        SubmissionResponse submission =
                submissionService.create(currentUserProvider.currentUsername(), request);

        URI location = uriBuilder.path("/api/v1/submissions/{id}")
                .buildAndExpand(submission.id())
                .toUri();

        return ResponseEntity.created(location).body(submission);
    }

    @GetMapping("/me")
    @Operation(summary = "The calling user's submission history, newest first")
    public PageResponse<SubmissionResponse> mine(@PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(
                submissionService.findByUsername(currentUserProvider.currentUsername(), pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one submission")
    public SubmissionResponse get(@PathVariable Long id) {
        return submissionService.getById(id);
    }

    @GetMapping(value = "/{id}/source", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Get the submitted source code",
            description = """
                    Served as text/plain rather than wrapped in JSON, so the response can be
                    piped straight into a file or an editor. Source is held in memory until
                    Phase 7 moves it to MongoDB, so older submissions may return 404.
                    """)
    public String source(@PathVariable Long id) {
        return submissionService.getSourceCode(id)
                .orElseThrow(() -> new ResourceNotFoundException("Submission source", id));
    }
}
