package com.tapin.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned as {@code errorCode} in every {@code application/problem+json}
 * response. Must stay identical to {@code components.schemas.ErrorCode} in {@code api/openapi.yaml}
 * (enforced by {@code ErrorCodeContractTest}). Status purposes:
 * <ul>
 *   <li>400 - malformed or invalid request shape</li>
 *   <li>401 - not authenticated / wrong credentials</li>
 *   <li>403 - authenticated but not allowed (role, ownership, CSRF)</li>
 *   <li>404 - resource does not exist</li>
 *   <li>409 - state conflict (concurrent edit, duplicate, already recorded)</li>
 *   <li>422 - valid request rejected by a business rule</li>
 *   <li>429 - rate limited</li>
 * </ul>
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),

    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),

    FORBIDDEN(HttpStatus.FORBIDDEN),
    CSRF_TOKEN_INVALID(HttpStatus.FORBIDDEN),
    ROLE_ASSIGNMENT_FORBIDDEN(HttpStatus.FORBIDDEN),

    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),

    CONFLICT(HttpStatus.CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    ATTENDANCE_ALREADY_RECORDED(HttpStatus.CONFLICT),
    TRAINING_OVERLAP(HttpStatus.CONFLICT),
    BELT_DUPLICATE_EFFECTIVE_DATE(HttpStatus.CONFLICT),

    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT),
    MEMBERSHIP_INACTIVE(HttpStatus.UNPROCESSABLE_CONTENT),
    TRAINING_CANCELLED(HttpStatus.UNPROCESSABLE_CONTENT),
    LAST_ADMIN_REMOVAL(HttpStatus.UNPROCESSABLE_CONTENT),
    BELT_CHRONOLOGY_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
    INVITATION_INVALID_OR_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    PASSWORD_RESET_TOKEN_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /** Problem {@code type} URI, e.g. {@code urn:tapin:error:version-conflict}. */
    public String typeUri() {
        return "urn:tapin:error:" + name().toLowerCase().replace('_', '-');
    }
}
