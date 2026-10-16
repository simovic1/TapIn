package com.tapin.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders every error as RFC 9457 {@code application/problem+json} with
 * {@code type, title, status, detail, instance, errorCode} (spec §11.8).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        return problem(ex.errorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(ErrorCode.CONFLICT, "Zapis je u međuvremenu izmijenjen. Osvježite podatke i pokušajte ponovno.",
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return problem(ErrorCode.UNAUTHENTICATED, "Potrebna je prijava.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(ErrorCode.FORBIDDEN, "Nemate ovlast za ovu radnju.", request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(ErrorCode.INTERNAL_ERROR, "Došlo je do neočekivane pogreške.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setType(URI.create(ErrorCode.VALIDATION_FAILED.typeUri()));
        body.setDetail("Zahtjev sadrži neispravne podatke.");
        body.setProperty("errorCode", ErrorCode.VALIDATION_FAILED.name());
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(e -> e.getField(), e -> String.valueOf(e.getDefaultMessage()), (a, b) -> a));
        body.setProperty("fieldErrors", fieldErrors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Adds {@code errorCode} to problems produced by Spring MVC itself (404, 405, 415, ...). */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("errorCode"))) {
            problem.setProperty("errorCode", codeFor(statusCode).name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.typeUri()));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", code.name());
        return problem;
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            case 422 -> ErrorCode.BUSINESS_RULE_VIOLATION;
            case 429 -> ErrorCode.TOO_MANY_REQUESTS;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_FAILED;
        };
    }
}
