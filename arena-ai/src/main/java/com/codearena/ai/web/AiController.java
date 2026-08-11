package com.codearena.ai.web;

import com.codearena.ai.complexity.ComplexityService;
import com.codearena.ai.hint.HintService;
import com.codearena.ai.web.dto.ComplexityRequest;
import com.codearena.ai.web.dto.ComplexityResponse;
import com.codearena.ai.web.dto.HintRequest;
import com.codearena.ai.web.dto.HintResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI service's HTTP surface.
 *
 * <h2>No authentication here, deliberately</h2>
 *
 * <p>This service is not published. It listens inside the compose network and its only client is
 * arena-api, which authenticates the user before calling. Adding a second token scheme here
 * would mean two places to get authentication wrong for one decision that has already been made
 * correctly upstream.
 *
 * <p>That is a statement about the deployment, not a shrug: exposing this port publicly would
 * hand anybody a free model endpoint. It is documented in the compose file, which does not
 * publish the port.
 */
@RestController
@RequestMapping("/api/v1/ai")
@Tag(name = "AI", description = "Hints and complexity analysis")
public class AiController {

    private final HintService hintService;
    private final ComplexityService complexityService;

    public AiController(HintService hintService, ComplexityService complexityService) {
        this.hintService = hintService;
        this.complexityService = complexityService;
    }

    @PostMapping("/hints")
    @Operation(summary = "A hint towards solving a problem",
            description = """
                    Returns the smallest useful nudge at the requested level - never code, and
                    never a full solution. Level 1 asks how to approach the problem, level 3 may
                    name the technique.

                    `source` says whether a language model answered or the built-in hint library
                    did. The service works either way; only the specificity differs.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A hint at the requested level"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content)
    })
    public HintResponse hint(@Valid @RequestBody HintRequest request) {
        return hintService.hint(request);
    }

    @PostMapping("/complexity")
    @Operation(summary = "Estimate the time and space complexity of a solution",
            description = """
                    Analyses the submitted code and returns big-O estimates with an explanation.

                    `source` distinguishes a model's reading from the built-in static analyser.
                    A heuristic answer also carries `reasons` and a `caveat` naming the most
                    likely way it is wrong - a structural loop count over-estimates a two-pointer
                    sweep, for instance.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "An estimate, from a model or static analysis"),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content)
    })
    public ComplexityResponse complexity(@Valid @RequestBody ComplexityRequest request) {
        return complexityService.analyse(request.language(), request.sourceCode());
    }
}
