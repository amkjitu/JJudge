package com.codearena.api.web;

import com.codearena.api.support.KafkaTestContainer;
import com.codearena.api.support.MongoTestContainer;
import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
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

    /**
     * Sentinel meaning "never recorded". Cleanup refuses to run against it.
     *
     * <p>This guard is not hypothetical. JUnit runs {@code @AfterEach} even when
     * {@code @BeforeEach} threw, so a setup failure used to leave the watermarks at their
     * default of zero - and {@code DELETE ... WHERE id > 0} then emptied the seeded tables that
     * every other test in the suite depends on. One unrelated infrastructure hiccup took out
     * sixteen tests that had nothing to do with it.
     */
    private static final long NOT_RECORDED = -1L;

    private long submissionWatermark = NOT_RECORDED;
    private long problemWatermark = NOT_RECORDED;
    private long userWatermark = NOT_RECORDED;
    private long refreshTokenWatermark = NOT_RECORDED;

    @BeforeEach
    void recordWatermarks() {
        submissionWatermark = maxId("submissions");
        problemWatermark = maxId("problems");
        userWatermark = maxId("users");
        refreshTokenWatermark = maxId("refresh_tokens");
    }

    @AfterEach
    void undoWrites() {
        if (submissionWatermark == NOT_RECORDED) {
            // Setup failed before the watermarks were taken. There is nothing this test could
            // have written, and deleting on a guessed boundary would destroy the fixtures.
            return;
        }
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
        // A real broker, because submitting publishes an event after the transaction commits -
        // without one the send fails and the endpoint returns 500. The container is a shared
        // singleton, so this costs nothing beyond the first test class that asks for it.
        KafkaTestContainer.registerProperties(registry);
        // Consumers stay off, though: these tests only need to *produce*. Leaving the verdict
        // listener running would have every context in the suite competing for the same records
        // as SubmissionPipelineIT, which is the one test that actually asserts on them.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        RedisTestContainer.registerProperties(registry);
        MongoTestContainer.registerProperties(registry);
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
        // Off by default so unrelated tests are not throttled; RateLimitApiIT turns it on.
        registry.add("arena.rate-limit.enabled", () -> "false");
    }
}
