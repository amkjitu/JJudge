package com.codearena.api.security;

import com.codearena.api.web.error.ErrorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Makes security failures look like every other error in the API.
 *
 * <p>Spring Security's defaults short-circuit before {@code @RestControllerAdvice} runs, so
 * without these a 401 or 403 comes back with an empty body while every other failure returns
 * {@code application/problem+json}. A client should not need two error parsers.
 */
@Component
public class ProblemDetailAuthenticationHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 401: no credentials, or credentials that did not verify. */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, ErrorType.UNAUTHENTICATED,
                "Authentication is required to access this resource");
    }

    /** 403: authenticated, but not permitted. */
    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, ErrorType.FORBIDDEN,
                "You do not have permission to perform this action");
    }

    private void write(HttpServletRequest request,
                       HttpServletResponse response,
                       HttpStatus status,
                       ErrorType errorType,
                       String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(errorType.type());
        problem.setTitle(errorType.title());
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", Instant.now());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
