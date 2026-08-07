package com.codearena.api.web;

import com.codearena.api.service.ProblemService;
import com.codearena.api.web.dto.CreateProblemRequest;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.UpdateProblemRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Problem authoring.
 *
 * <p>Kept on a separate {@code /admin} path rather than mixed into {@link ProblemController}
 * so Phase 3 can lock the whole branch down with one path matcher instead of annotating
 * individual methods.
 */
@RestController
@RequestMapping("/api/v1/admin/problems")
@Tag(name = "Admin: problems", description = "Problem authoring (ADMIN only from Phase 3)")
public class AdminProblemController {

    private final ProblemService problemService;

    public AdminProblemController(ProblemService problemService) {
        this.problemService = problemService;
    }

    @PostMapping
    @Operation(summary = "Create a problem",
            description = "Difficulty is derived from rating and must not be supplied.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created"),
            @ApiResponse(responseCode = "400", description = "Validation failed or unknown tag", content = @Content),
            @ApiResponse(responseCode = "409", description = "Slug already in use", content = @Content)
    })
    public ResponseEntity<ProblemDetailResponse> create(@Valid @RequestBody CreateProblemRequest request,
                                                        UriComponentsBuilder uriBuilder) {
        ProblemDetailResponse created = problemService.create(request);

        URI location = uriBuilder.path("/api/v1/problems/{slug}")
                .buildAndExpand(created.slug())
                .toUri();

        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{slug}")
    @Operation(summary = "Update a problem",
            description = "The slug is immutable - it is a public URL.")
    public ProblemDetailResponse update(@PathVariable String slug,
                                        @Valid @RequestBody UpdateProblemRequest request) {
        return problemService.update(slug, request);
    }

    @DeleteMapping("/{slug}")
    @Operation(summary = "Delete a problem",
            description = "Cascades to its submissions and tag links.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    public ResponseEntity<Void> delete(@PathVariable String slug) {
        problemService.delete(slug);
        return ResponseEntity.noContent().build();
    }
}
