package com.cinemahall.common;

import java.time.Instant;
import java.util.List;

/** The single error shape returned by every endpoint. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }

    public static ErrorResponse of(int status, String code, String message, String path) {
        return new ErrorResponse(Instant.now(), status, code, message, path, List.of());
    }
}
