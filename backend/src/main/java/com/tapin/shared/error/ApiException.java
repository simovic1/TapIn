package com.tapin.shared.error;

/**
 * Base class for expected business/API errors. Services throw it (or a subclass); the
 * {@link GlobalExceptionHandler} turns it into {@code application/problem+json}.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static ApiException notFound(String detail) {
        return new ApiException(ErrorCode.NOT_FOUND, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(ErrorCode.CONFLICT, detail);
    }

    public static ApiException businessRule(String detail) {
        return new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, detail);
    }
}
