package com.codearena.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Stable pagination envelope.
 *
 * <p>Serialising Spring's {@code Page} directly would publish {@code Pageable} and {@code Sort}
 * internals as part of the API contract - a shape Spring itself warns is unstable across
 * versions. This exposes only the four numbers a client actually needs.
 */
@Schema(name = "Page", description = "A page of results")
public record PageResponse<T>(

        @Schema(description = "Items on this page")
        List<T> content,

        @Schema(description = "Zero-based page index", example = "0")
        int page,

        @Schema(description = "Requested page size", example = "20")
        int size,

        @Schema(description = "Total items across all pages", example = "40")
        long totalElements,

        @Schema(description = "Total number of pages", example = "2")
        int totalPages,

        @Schema(description = "True when this is the last page")
        boolean last
) {

    /** Wraps a page that is already carrying DTOs. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return from(page, Function.identity());
    }

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast()
        );
    }
}
