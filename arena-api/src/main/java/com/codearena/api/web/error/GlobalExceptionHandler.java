package com.codearena.api.web.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
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
        return new FieldViolation(error.getField(), error.getDefaultMessage(), error.getRejectedValue());
    }

    private FieldViolation toFieldViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        // Strip the "method.argument" prefix javax adds for parameter-level constraints.
        String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
        return new FieldViolation(field, violation.getMessage(), violation.getInvalidValue());
    }
}
