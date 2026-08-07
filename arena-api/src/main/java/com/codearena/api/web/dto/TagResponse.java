package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(name = "Tag")
public record TagResponse(

        Long id,

        @Schema(example = "shortest-path")
        String name,

        @Schema(description = "Topics that should be comfortable before this one",
                example = "[\"bfs\", \"heap\"]")
        Set<String> prerequisites
) {
}
