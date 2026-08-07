package com.codearena.api.web.mapper;

import com.codearena.api.domain.Submission;
import com.codearena.api.web.dto.SubmissionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface SubmissionMapper {

    @Mapping(target = "problemId", source = "problem.id")
    @Mapping(target = "problemSlug", source = "problem.slug")
    @Mapping(target = "problemTitle", source = "problem.title")
    @Mapping(target = "username", source = "user.username")
    SubmissionResponse toResponse(Submission submission);
}
