package com.tapin.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned as {@code errorCode} in every {@code application/problem+json}
 * response. Modules add specific codes here (prefixed by module, e.g. {@code ATTENDANCE_VERSION_CONFLICT})
 * instead of inventing ad-hoc strings.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Problem {@code type} URI, e.g. {@code urn:tapin:error:not-found}. */
    public String typeUri() {
        return "urn:tapin:error:" + name().toLowerCase().replace('_', '-');
    }
}
