package com.healthclaim.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Consistent error envelope returned by {@code GlobalExceptionHandler}.
 *
 * <p>{@code fieldErrors} is only present for validation failures; {@code traceId}
 * lets a caller correlate a failed request with server logs without exposing
 * stack traces.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors,
        String traceId
) {
    public static ApiError of(int status, String error, String message, String path,
                              Map<String, String> fieldErrors, String traceId) {
        return new ApiError(Instant.now(), status, error, message, path, fieldErrors, traceId);
    }
}
