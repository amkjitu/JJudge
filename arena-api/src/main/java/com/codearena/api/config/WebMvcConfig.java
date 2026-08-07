package com.codearena.api.config;

import com.codearena.api.ratelimit.SubmissionRateLimitInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<SubmissionRateLimitInterceptor> submissionRateLimitInterceptor;

    /**
     * Injected through {@link ObjectProvider} rather than directly.
     *
     * <p>{@code WebMvcConfigurer} implementations are one of the few bean types
     * {@code @WebMvcTest} scans, whereas the rate limiting beans live in a plain
     * {@code @Configuration} that it does not. A hard dependency would therefore make every
     * controller slice fail to start.
     *
     * <p>Resolving it lazily means the full application always registers the interceptor, and a
     * slice with no rate limiter on the context simply does not - the correct behaviour for a
     * test that is not about rate limiting.
     */
    public WebMvcConfig(ObjectProvider<SubmissionRateLimitInterceptor> submissionRateLimitInterceptor) {
        this.submissionRateLimitInterceptor = submissionRateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Scoped to the submission endpoint only. Reads are cheap and browsing the catalogue
        // unauthenticated is a supported use, so throttling those would cost more than it buys.
        submissionRateLimitInterceptor.ifAvailable(interceptor ->
                registry.addInterceptor(interceptor).addPathPatterns("/api/v1/submissions"));
    }
}
