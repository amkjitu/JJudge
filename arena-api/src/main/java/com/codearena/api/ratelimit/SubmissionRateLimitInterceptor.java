package com.codearena.api.ratelimit;

import com.codearena.api.service.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Rate limits {@code POST /api/v1/submissions}.
 *
 * <p>An interceptor rather than a servlet filter, deliberately: interceptors run after the
 * security filter chain, so the authenticated principal is available and the bucket can be
 * keyed by user id. Keying on IP instead would punish everyone behind one NAT and would be
 * trivially evaded.
 */
public class SubmissionRateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;
    private final CurrentUserProvider currentUserProvider;

    public SubmissionRateLimitInterceptor(RateLimiter rateLimiter,
                                          CurrentUserProvider currentUserProvider) {
        this.rateLimiter = rateLimiter;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }

        RateLimiter.Decision decision =
                rateLimiter.tryConsume("submissions:" + currentUserProvider.currentUsername());

        // Advertised on every response, not just refusals, so a well-behaved client can slow
        // itself down before it gets refused.
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remainingPermits()));

        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfter());
        }
        return true;
    }
}
