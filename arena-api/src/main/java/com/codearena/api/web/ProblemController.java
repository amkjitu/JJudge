package com.codearena.api.web;

import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.web.dto.PageResponse;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.ProblemSummaryResponse;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.common.domain.Difficulty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/problems")
@Validated
@Tag(name = "Problems", description = "Browse the problem catalogue")
public class ProblemController {

    private final ProblemService problemService;
    private final SubmissionService submissionService;

    public ProblemController(ProblemService problemService, SubmissionService submissionService) {
        this.problemService = problemService;
        this.submissionService = submissionService;
    }

    @GetMapping
    @Operation(summary = "List problems",
            description = "Filters combine with AND. Omitted filters are not applied.")
    public PageResponse<ProblemSummaryResponse> list(
            @Parameter(description = "Tag name, case-insensitive", example = "dp")
            @RequestParam(required = false) String tag,

            @Parameter(description = "Difficulty bucket") @RequestParam(required = false) Difficulty difficulty,

            @Parameter(description = "Inclusive lower bound on rating", example = "1200")
            @RequestParam(required = false) @Min(0) @Max(4000) Integer minRating,

            @Parameter(description = "Inclusive upper bound on rating", example = "1600")
            @RequestParam(required = false) @Min(0) @Max(4000) Integer maxRating,

            @Parameter(description = "Substring match on title or slug", example = "dijkstra")
            @RequestParam(required = false) String search,

            @PageableDefault(size = 20, sort = "rating", direction = Sort.Direction.ASC) Pageable pageable) {

        ProblemFilter filter = new ProblemFilter(tag, difficulty, minRating, maxRating, search);
        return PageResponse.from(problemService.search(filter, pageable));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get one problem by slug")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "No problem with that slug", content = @Content)
    })
    public ProblemDetailResponse get(@PathVariable String slug) {
        return problemService.getDetail(slug);
    }

    @GetMapping("/{slug}/submissions")
    @Operation(summary = "Submissions against a problem, newest first")
    public PageResponse<SubmissionResponse> submissions(
            @PathVariable String slug,
            @PageableDefault(size = 20) Pageable pageable) {

        return PageResponse.from(submissionService.findByProblemSlug(slug, pageable));
    }
}
