package com.codearena.api.web;

import com.codearena.api.service.TagService;
import com.codearena.api.web.dto.TagResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "Tags", description = "Topic taxonomy and its prerequisite graph")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    @Operation(summary = "All tags with their prerequisites",
            description = """
                    Returns the full prerequisite DAG. Edges point from a topic to the topics
                    that should be comfortable first; the recommendation engine topologically
                    sorts this in Phase 5 so advanced topics are never suggested prematurely.
                    """)
    public List<TagResponse> list() {
        return tagService.findAllWithPrerequisites();
    }
}
