package com.codearena.api.ui;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on {@code @PreAuthorize} for a UI slice test.
 *
 * <p>{@link UiSliceSecurityConfig} permits every request, so a slice proves nothing about
 * authorization on its own. That is the right trade for pages whose protection is the
 * {@code /admin/**} path rule — tested against the real chain elsewhere — but not for the
 * authoring editors, which write the data every verdict is derived from. There the annotation on
 * the controller is worth exercising directly, rather than trusting a URL pattern that a later
 * reorganisation could move out from under it.
 *
 * <p>A top-level class rather than one nested in the test: a nested {@code @TestConfiguration} is
 * auto-registered by Spring Boot <em>and</em> was being imported explicitly, and the resulting
 * context stopped resetting {@code @MockBean}s between test methods — so verifications counted
 * invocations from earlier tests in the same class.
 */
@TestConfiguration
@EnableMethodSecurity
public class MethodSecurityTestConfig {
}
