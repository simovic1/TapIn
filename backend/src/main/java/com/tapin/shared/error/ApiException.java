package com.tapin.shared.error;

import java.util.Map;

/**
 * Base class for expected business/API errors. Services throw it (or a subclass); the
 * {@link GlobalExceptionHandler} turns it into {@code application/problem+json}.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object currentState;
    private final Map<String, String> fieldErrors;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    /** @param currentState fresh resource state returned as {@code currentState} (409 conflicts), may be null */
    public ApiException(ErrorCode errorCode, String detail, Object currentState) {
        this(errorCode, detail, currentState, Map.of());
    }

    private ApiException(ErrorCode errorCode, String detail, Object currentState, Map<String, String> fieldErrors) {
        super(detail);
        this.errorCode = errorCode;
        this.currentState = currentState;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object currentState() {
        return currentState;
    }

    /** Field errors returned as {@code fieldErrors}, like Bean Validation failures; empty for other errors. */
    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    /** {@code 400 VALIDATION_FAILED} for a rule Bean Validation cannot express (e.g. end after start). */
    public static ApiException invalidField(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, null, Map.of(field, message));
    }

    public static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(ErrorCode.CONFLICT, detail);
    }

    public static ApiException versionConflict(Object currentState) {
        return new ApiException(ErrorCode.VERSION_CONFLICT,
                "Zapis je u međuvremenu izmijenjen. Osvježite podatke i pokušajte ponovno.", currentState);
    }

    public static ApiException businessRule(String detail) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, detail);
    }
}
