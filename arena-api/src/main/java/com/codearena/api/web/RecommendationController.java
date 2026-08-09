package com.codearena.api.web;

import com.codearena.api.service.CurrentUserProvider;
import com.codearena.api.service.RecommendationService;
import com.codearena.api.web.dto.RecommendationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recommendations")
@Validated
@Tag(name = "Recommendations", description = "What to solve next")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final CurrentUserProvider currentUserProvider;

    public RecommendationController(RecommendationService recommendationService,
                                    CurrentUserProvider currentUserProvider) {
        this.recommendationService = recommendationService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/me")
    @Operation(summary = "Problems the calling user should attempt next",
            description = """
                    Ranked by `w₁·tagWeakness + w₂·ratingFit + w₃·recency − w₄·repetitionPenalty`,
                    restricted to unsolved problems in a band around the caller's rating, gated
                    by the tag prerequisite DAG and capped per topic so the list is not five
                    flavours of the same thing.

                    Each entry carries its score breakdown, so the ranking can be explained
                    rather than taken on trust.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ranked suggestions, best first"),
            @ApiResponse(responseCode = "401", description = "Not authenticated", content = @Content)
    })
    public List<RecommendationResponse> forCurrentUser(
            @Parameter(description = "How many suggestions to return")
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {

        return recommendationService.recommendFor(currentUserProvider.currentUsername(), limit);
    }

    @GetMapping("/users/{username}")
    @Operation(summary = "Recommendations for a named user",
            description = """
                    Public, because the ranking is derived entirely from data already on the
                    profile page. It also makes the engine inspectable: the three demo accounts
                    have deliberately different histories, so comparing their lists shows the
                    scoring actually responding to the input.
                    """)
    public List<RecommendationResponse> forUser(
            @PathVariable String username,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {

        return recommendationService.recommendFor(username, limit);
    }
}
