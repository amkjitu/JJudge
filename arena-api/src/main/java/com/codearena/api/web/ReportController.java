package com.codearena.api.web;

import com.codearena.api.reporting.TagDifficultyReportDao;
import com.codearena.api.reporting.TagDifficultySort;
import com.codearena.api.web.dto.TagDifficultyRow;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
@Validated
@Tag(name = "Reports", description = "Aggregate statistics served by Spring JDBC")
public class ReportController {

    private final TagDifficultyReportDao tagDifficultyReportDao;

    public ReportController(TagDifficultyReportDao tagDifficultyReportDao) {
        this.tagDifficultyReportDao = tagDifficultyReportDao;
    }

    @GetMapping("/tag-difficulty")
    @Operation(summary = "Per-topic difficulty report",
            description = """
                    How hard each topic is proving in practice: how many problems carry the
                    tag, how many submissions it has attracted, what fraction were accepted,
                    how many distinct users have solved anything on it, and the mean number of
                    attempts users needed on problems they eventually solved.

                    This one endpoint is implemented with Spring JDBC rather than JPA. The
                    query is four CTEs of set-level aggregation returning a shape that maps to
                    no entity, which is exactly the case where dropping to SQL is simpler and
                    faster than bending an ORM around it.
                    """)
    public List<TagDifficultyRow> tagDifficulty(
            @Parameter(description = "Ordering; HARDEST puts the lowest acceptance rate first")
            @RequestParam(defaultValue = "TAG") TagDifficultySort sort,

            @Parameter(description = "Exclude tags with fewer than this many problems", example = "0")
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int minProblems) {

        return tagDifficultyReportDao.report(sort, minProblems);
    }
}
