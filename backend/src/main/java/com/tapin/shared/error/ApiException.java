package com.tapin.shared.error;

/**
 * Base class for expected business/API errors. Services throw it (or a subclass); the
 * {@link GlobalExceptionHandler} turns it into {@code application/problem+json}.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object currentState;

    public ApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null);
    }

    /** @param currentState fresh resource state returned as {@code currentState} (409 conflicts), may be null */
    public ApiException(ErrorCode errorCode, String detail, Object currentState) {
        super(detail);
        this.errorCode = errorCode;
        this.currentState = currentState;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object currentState() {
        return currentState;
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
