package com.codearena.api.web.error;

import com.codearena.api.ratelimit.RateLimitExceededException;
import com.codearena.api.security.InvalidRefreshTokenException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Translates every exception that escapes a controller into an RFC 7807 {@code ProblemDetail}.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than only handling custom
 * exceptions, so framework-level failures - unreadable bodies, unsupported media types, type
 * mismatches on path variables - come back in the same shape as domain errors instead of
 * falling through to Boot's default HTML-ish error body.
 *
 * <p>All responses are served as {@code application/problem+json}, which Spring sets
 * automatically for {@code ProblemDetail} return values.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Substrings that mark a field whose value must never be echoed back. */
    private static final List<String> SENSITIVE_FIELD_FRAGMENTS =
            List.of("password", "secret", "token", "credential", "authorization");

    private static final int MAX_ECHOED_VALUE_LENGTH = 200;

    /**
     * A single field- or parameter-level complaint. Kept as a record so the JSON is a flat
     * array of objects rather than a map, which stays readable when one field has several
     * violations.
     */
    public record FieldViolation(String field, String message, Object rejectedValue) {
    }

    // -----------------------------------------------------------------------------------
    // Domain exceptions
    // -----------------------------------------------------------------------------------

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = problemDetail(HttpStatus.NOT_FOUND, ErrorType.RESOURCE_NOT_FOUND, ex.getMessage());
        problem.setProperty("resourceType", ex.getResourceType());
        problem.setProperty("identifier", ex.getIdentifier());
        return problem;
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicateResource(DuplicateResourceException ex) {
        ProblemDetail problem = problemDetail(HttpStatus.CONFLICT, ErrorType.RESOURCE_CONFLICT, ex.getMessage());
        problem.setProperty("resourceType", ex.getResourceType());
        problem.setProperty("identifier", ex.getIdentifier());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problemDetail(HttpStatus.BAD_REQUEST, ErrorType.MALFORMED_REQUEST, ex.getMessage());
    }

    // -----------------------------------------------------------------------------------
    // Security
    //
    // These need explicit handlers, not just the filter-chain entry points: an exception
    // thrown *inside* a controller - by @PreAuthorize, or by the login flow - is handled by
    // this advice, and would otherwise be swallowed by the catch-all below and reported as a
    // 500. That would turn "wrong password" into "the server is broken".
    // -----------------------------------------------------------------------------------

    /**
     * Bad credentials, and every other authentication failure raised during a request.
     * Deliberately does not distinguish "no such user" from "wrong password" - that difference
     * is a free account-enumeration oracle.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        log.debug("Authentication failed", ex);
        return problemDetail(HttpStatus.UNAUTHORIZED, ErrorType.INVALID_CREDENTIALS,
                "Invalid username or password");
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return problemDetail(HttpStatus.UNAUTHORIZED, ErrorType.INVALID_CREDENTIALS, ex.getMessage());
    }

    /**
     * Authenticated but not permitted. Anonymous callers never reach here - the filter chain
     * turns them away with a 401 first - so 403 is the right answer in every case that does.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problemDetail(HttpStatus.FORBIDDEN, ErrorType.FORBIDDEN,
                "You do not have permission to perform this action");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex) {
        long retryAfterSeconds = Math.max(1, ex.getRetryAfter().toSeconds());

        ProblemDetail problem = problemDetail(HttpStatus.TOO_MANY_REQUESTS, ErrorType.RATE_LIMITED,
                "Too many submissions; slow down");
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(problem);
    }

    /**
     * Constraint violations on {@code @RequestParam}/{@code @PathVariable} arguments, which
     * arrive here rather than as {@link MethodArgumentNotValidException}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(this::toFieldViolation)
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();

        ProblemDetail problem = problemDetail(HttpStatus.BAD_REQUEST, ErrorType.VALIDATION_FAILED,
                "One or more request parameters are invalid");
        problem.setProperty("errors", violations);
        return problem;
    }

    /**
     * Last line of defence for unique constraints. The services check first and throw
     * {@link DuplicateResourceException}, but that check-then-act is racy under concurrency,
     * so the database remains the actual arbiter.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Database rejected the request: {}", ex.getMostSpecificCause().getMessage());
        return problemDetail(HttpStatus.CONFLICT, ErrorType.RESOURCE_CONFLICT,
                "The request conflicts with the current state of the data");
    }

    /**
     * Catch-all. The exception message is logged but never returned: it can carry SQL
     * fragments, file paths or internal class names.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, ErrorType.INTERNAL_ERROR,
                "An unexpected error occurred");
    }

    // -----------------------------------------------------------------------------------
    // Framework exceptions - re-shaped into the same envelope
    // -----------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldViolation)
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();

        ProblemDetail problem = problemDetail(HttpStatus.BAD_REQUEST, ErrorType.VALIDATION_FAILED,
                "One or more fields are invalid");
        problem.setProperty("errors", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        // The raw Jackson message names internal classes and echoes the offending payload, so
        // it is logged rather than returned.
        log.debug("Unreadable request body", ex);
        ProblemDetail problem = problemDetail(HttpStatus.BAD_REQUEST, ErrorType.MALFORMED_REQUEST,
                "Request body is missing or not valid JSON");
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Raised when a {@code ?sort=} parameter names a property no entity has. Without this it
     * would surface as a 500, blaming the server for a client typo.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleUnknownSortProperty(PropertyReferenceException ex) {
        return problemDetail(HttpStatus.BAD_REQUEST, ErrorType.MALFORMED_REQUEST,
                "Cannot sort by '%s': no such property".formatted(ex.getPropertyName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String requiredType = ex.getRequiredType() == null ? "the expected type"
                : ex.getRequiredType().getSimpleName();
        return problemDetail(HttpStatus.BAD_REQUEST, ErrorType.MALFORMED_REQUEST,
                "Parameter '%s' could not be converted to %s".formatted(ex.getName(), requiredType));
    }

    /**
     * Anything {@link ResponseEntityExceptionHandler} handles that is not overridden above -
     * unsupported media type, missing parameter, method not allowed - still gets the shared
     * envelope rather than an empty body.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        if (body == null) {
            ProblemDetail problem = ProblemDetail.forStatus(statusCode);
            problem.setType(errorTypeFor(statusCode).type());
            problem.setTitle(errorTypeFor(statusCode).title());
            problem.setDetail(ex.getMessage());
            problem.setProperty("timestamp", Instant.now());
            body = problem;
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // -----------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------

    private ProblemDetail problemDetail(HttpStatus status, ErrorType errorType, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(errorType.type());
        problem.setTitle(errorType.title());
        problem.setDetail(detail);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private ErrorType errorTypeFor(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorType.INTERNAL_ERROR;
        }
        return ErrorType.MALFORMED_REQUEST;
    }

    private FieldViolation toFieldViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage(),
                safeRejectedValue(error.getField(), error.getRejectedValue()));
    }

    private FieldViolation toFieldViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        // Strip the "method.argument" prefix javax adds for parameter-level constraints.
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return new FieldViolation(field, violation.getMessage(),
                safeRejectedValue(field, violation.getInvalidValue()));
    }

    /**
     * Echoing the rejected value is genuinely useful for debugging - "you sent 99999, the max is
     * 4000" beats "invalid". It is also how a rejected password ends up in a response body, an
     * access log and whatever aggregates those logs.
     *
     * <p>So: sensitive fields report no value at all, and long ones are truncated rather than
     * mirroring a 64 KiB source file back at the caller.
     */
    private Object safeRejectedValue(String field, Object rejectedValue) {
        if (rejectedValue == null) {
            return null;
        }
        String lowerCaseField = field.toLowerCase();
        for (String sensitive : SENSITIVE_FIELD_FRAGMENTS) {
            if (lowerCaseField.contains(sensitive)) {
                return null;
            }
        }
        if (rejectedValue instanceof String text && text.length() > MAX_ECHOED_VALUE_LENGTH) {
            return text.substring(0, MAX_ECHOED_VALUE_LENGTH) + "... (truncated)";
        }
        return rejectedValue;
    }
}
