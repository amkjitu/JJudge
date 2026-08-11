package com.codearena.api.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "Complexity", description = "Estimated cost of running a solution")
public record ComplexityView(String timeComplexity,
                             String spaceComplexity,
                             String explanation,
                             List<String> reasons,
                             String caveat,
                             String source) {

    public ComplexityView {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
