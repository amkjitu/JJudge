package com.codearena.api.ui;

import com.codearena.api.ratelimit.RateLimitExceededException;
import com.codearena.api.web.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * HTML error pages for the Thymeleaf UI.
 *
 * <p>Scoped to {@code com.codearena.api.ui} so it cannot shadow the RFC 7807
 * {@code @RestControllerAdvice} that serves {@code com.codearena.api.web}. Advice packages match
 * by prefix, which is exactly why the UI controllers live in a sibling package rather than a
 * {@code web.ui} subpackage - the latter would have been swallowed by the REST advice and the
 * browser would have received JSON.
 *
 * <p>{@link AccessDeniedException} needs its own handler rather than being left to Spring
 * Security. Once a controller method has been entered, the catch-all below would swallow it and
 * report a 500 - "you may not see this" rendered as "the server broke". Anonymous callers never
 * reach a controller in the first place, because the filter chain challenges them, so handling
 * it here cannot turn a "log in" into a "denied".
 */
@ControllerAdvice(basePackages = "com.codearena.api.ui")
public class UiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(ResourceNotFoundException ex, Model model) {
        model.addAttribute("status", 404);
        model.addAttribute("heading", "Not found");
        model.addAttribute("message", ex.getMessage());
        return "error/generic";
    }

    /**
     * Must be declared explicitly, and must sit ahead of the {@code Exception} catch-all in
     * specificity - which Spring resolves by exception-type distance, not declaration order.
     */
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleAccessDenied(AccessDeniedException ex, Model model) {
        // Logged at debug, not error: a user poking at a URL they do not own is expected
        // traffic, not an operational problem.
        log.debug("Access denied rendering a UI page: {}", ex.getMessage());
        model.addAttribute("status", 403);
        model.addAttribute("heading", "Not allowed");
        model.addAttribute("message", "That page belongs to somebody else.");
        return "error/generic";
    }

    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public String handleRateLimited(RateLimitExceededException ex, Model model) {
        model.addAttribute("status", 429);
        model.addAttribute("heading", "Slow down");
        model.addAttribute("message",
                "You have submitted too many solutions in a short window. Try again in "
                        + ex.getRetryAfter().toSeconds() + " seconds.");
        return "error/generic";
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String handleUnexpected(Exception ex, Model model) {
        log.error("Unhandled exception rendering a UI page", ex);
        model.addAttribute("status", 500);
        model.addAttribute("heading", "Something went wrong");
        // The exception message is logged, never rendered: it can carry SQL and class names.
        model.addAttribute("message", "An unexpected error occurred. It has been logged.");
        return "error/generic";
    }
}
