package com.codearena.api.web;

import com.codearena.api.service.CurrentUserProvider;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.sse.SubmissionStream;
import com.codearena.api.web.dto.CreateSubmissionRequest;
import com.codearena.api.web.dto.PageResponse;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.common.domain.SubmissionStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/submissions")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Submissions", description = "Submit solutions and read verdicts (authenticated)")
public class SubmissionController {

    private final SubmissionService submissionService;
    private final CurrentUserProvider currentUserProvider;
    private final SubmissionStream submissionStream;

    public SubmissionController(SubmissionService submissionService,
                                CurrentUserProvider currentUserProvider,
                                SubmissionStream submissionStream) {
        this.submissionService = submissionService;
        this.currentUserProvider = currentUserProvider;
        this.submissionStream = submissionStream;
    }

    @PostMapping
    @Operation(summary = "Submit a solution",
            description = """
                    Records the attempt and returns immediately as QUEUED - the response waits
                    for a database insert, not for judging. The submission is published to Kafka
                    and evaluated by arena-judge; the verdict arrives over
                    `GET /api/v1/submissions/{id}/stream`.

                    The submitting user comes from the authentication context, never from the
                    request body.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Submission accepted and queued"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content),
            @ApiResponse(responseCode = "404", description = "No such problem", content = @Content),
            @ApiResponse(responseCode = "429", description = "Submission rate limit exceeded", content = @Content)
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

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live verdict updates for one submission",
            description = """
                    A Server-Sent Events stream that emits a single `verdict` event when judging
                    finishes, then closes. If the submission has already been judged the verdict
                    is delivered immediately and the stream closes at once.

                    One-directional and low-volume, which is why this is SSE rather than a
                    WebSocket: it is plain HTTP, reconnects on its own, and needs no proxy
                    configuration.
                    """)
    public SseEmitter stream(@PathVariable Long id) {
        // Confirms the submission exists (and 404s if not) before holding a connection open for
        // it - otherwise a typo in the id would leave the browser waiting on a stream that can
        // never produce anything.
        submissionService.getById(id);

        SseEmitter emitter = submissionStream.subscribe(id);

        // Re-read *after* registering, and in this order deliberately. Judging can finish in
        // under two seconds, so a page that loads and then connects can easily miss the verdict
        // entirely - the emitter would sit open for its full timeout with nothing to say.
        //
        // Registering first means a verdict arriving during this read reaches the emitter through
        // the listener; reading second means one that arrived before registration is delivered
        // here. Publishing twice is harmless because publish() removes the emitters atomically,
        // so whichever path runs first is the only one that sends.
        SubmissionResponse current = submissionService.getById(id);
        if (current.status() == SubmissionStatus.DONE) {
            submissionStream.publish(current);
        }

        return emitter;
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
