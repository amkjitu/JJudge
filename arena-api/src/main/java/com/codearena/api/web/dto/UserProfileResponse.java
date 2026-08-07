package com.codearena.api.web.dto;

import com.codearena.common.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(name = "UserProfile")
public record UserProfileResponse(

        Long id,

        @Schema(example = "bob")
        String username,

        Role role,

        @Schema(example = "1450")
        Integer rating,

        Instant createdAt,

        @Schema(description = "Distinct problems solved", example = "14")
        long solvedCount,

        @Schema(description = "Total submissions made", example = "22")
        long submissionCount,

        @Schema(description = "Per-topic record, weakest topics first")
        List<UserTagStatResponse> tagStats
) {
}
