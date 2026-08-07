package com.codearena.api.web.mapper;

import com.codearena.api.domain.Tag;
import com.codearena.api.web.dto.TagResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Mapper
public interface TagMapper {

    @Mapping(target = "prerequisites", source = "prerequisites", qualifiedByName = "prerequisiteNames")
    TagResponse toResponse(Tag tag);

    @Named("prerequisiteNames")
    default Set<String> prerequisiteNames(Set<Tag> prerequisites) {
        if (prerequisites == null) {
            return new LinkedHashSet<>();
        }
        return prerequisites.stream().map(Tag::getName).collect(Collectors.toCollection(TreeSet::new));
    }
}
