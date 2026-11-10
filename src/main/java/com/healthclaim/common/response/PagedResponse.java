package com.healthclaim.common.response;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Transport-friendly pagination envelope so controllers never leak Spring Data's
 * {@code Page} type into the public API contract.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
