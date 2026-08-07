package com.codearena.api.web;

import com.codearena.api.support.PostgresTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Base for full-context API tests: the real Spring context, the real controllers, the real
 * services, the real security filter chain and a real PostgreSQL - only the servlet container
 * is replaced by MockMvc.
 *
 * <h2>Why there is no {@code @Transactional} here</h2>
 *
 * <p>Wrapping these tests in a transaction is the obvious way to get isolation, and it was how
 * they were originally written. It is also actively harmful: the test's transaction keeps a
 * persistence session open for the whole request, so lazily-loaded associations resolve
 * happily inside the test and blow up with {@code LazyInitializationException} in production,
 * where {@code spring.jpa.open-in-view} is disabled and the session closes with the service
 * call. That is not a hypothetical - it hid exactly that bug on {@code GET /api/v1/problems}
 * until the endpoint was exercised by hand against the running container.
 *
 * <p>So these tests run with production session semantics and undo their own writes instead:
 * rows created during a test are deleted afterwards by id watermark. The corollary is that a
 * test must never mutate seeded data - it creates what it intends to change.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class AbstractApiIT {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long submissionWatermark;
    private long problemWatermark;
    private long userWatermark;
    private long refreshTokenWatermark;

    @BeforeEach
    void recordWatermarks() {
        submissionWatermark = maxId("submissions");
        problemWatermark = maxId("problems");
        userWatermark = maxId("users");
        refreshTokenWatermark = maxId("refresh_tokens");
    }

    @AfterEach
    void undoWrites() {
        // Order matters: children before parents, even though the FKs cascade, because a test
        // may have added a submission against a pre-existing problem.
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE id > ?", refreshTokenWatermark);
        jdbcTemplate.update("DELETE FROM submissions WHERE id > ?", submissionWatermark);
        jdbcTemplate.update("DELETE FROM problems WHERE id > ?", problemWatermark);
        jdbcTemplate.update("DELETE FROM users WHERE id > ?", userWatermark);
    }

    /**
     * Authenticates the request as an existing user without going through login.
     *
     * <p>Populates the same {@code Authentication#getName()} that a real bearer token would, so
     * {@code SecurityContextCurrentUserProvider} cannot tell the difference. The genuine
     * token flow - signing, decoding, expiry, rotation - is covered end to end by
     * {@code AuthApiIT} instead; repeating it in every test would only slow the suite down.
     */
    protected static RequestPostProcessor asUser(String username) {
        return user(username).roles("USER");
    }

    protected static RequestPostProcessor asAdmin(String username) {
        return user(username).roles("ADMIN");
    }

    private long maxId(String table) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }

    @DynamicPropertySource
    static void testProperties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
        // Off by default so unrelated tests are not throttled; RateLimitApiIT turns it on.
        registry.add("arena.rate-limit.enabled", () -> "false");
    }
}
