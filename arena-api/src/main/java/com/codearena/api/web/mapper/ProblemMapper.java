package com.codearena.api.web.mapper;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.Tag;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.ProblemSummaryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * MapStruct generates the implementation at compile time, so there is no reflection at runtime
 * and an unmapped target field is a build error rather than a silent null - the
 * {@code unmappedTargetPolicy=ERROR} compiler argument is set in the parent pom.
 *
 * <p>Aggregates that need counts or derived values ({@code UserProfileResponse}) are assembled
 * in their service instead: they are not field-to-field mappings and forcing them through
 * MapStruct expressions would be less readable than plain Java.
 */
@Mapper
public interface ProblemMapper {

    @Mapping(target = "tags", source = "tags", qualifiedByName = "tagNames")
    ProblemSummaryResponse toSummary(Problem problem);

    /**
     * The statement is ignored here because it does not live in PostgreSQL - {@code ProblemService}
     * reads it from MongoDB and attaches it afterwards.
     *
     * <p>{@code withStatement} is ignored for a duller reason: MapStruct reads any single-argument
     * method returning the target type as a fluent setter, so the copy method on the record looks
     * to it like a writable property. With {@code unmappedTargetPolicy=ERROR} that fails the build
     * - and only on a <em>clean</em> one, because an incremental compile does not re-run the
     * annotation processor. Worth stating rather than leaving as a mystery line.
     */
    @Mapping(target = "tags", source = "tags", qualifiedByName = "tagNames")
    @Mapping(target = "statementMarkdown", ignore = true)
    @Mapping(target = "withStatement", ignore = true)
    ProblemDetailResponse toDetail(Problem problem);

    /**
     * Sorted so the JSON is stable across requests - an unordered Set would otherwise make
     * response snapshots in tests flaky for no reason.
     */
    @Named("tagNames")
    default Set<String> tagNames(Set<Tag> tags) {
        if (tags == null) {
            return new LinkedHashSet<>();
        }
        return tags.stream().map(Tag::getName).collect(Collectors.toCollection(TreeSet::new));
    }
}
