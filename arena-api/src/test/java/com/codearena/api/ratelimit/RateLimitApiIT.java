package com.codearena.api.ratelimit;

import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stands apart from {@link AbstractApiIT} because it needs the rate limiter switched on, and
 * the base class turns it off so unrelated tests are not throttled by each other. The limiter
 * is a singleton bean, so sharing a context would mean these tests spending other tests'
 * quota - and vice versa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Submission rate limiting")
class RateLimitApiIT {

    private static final int LIMIT = 3;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Buckets outlive a test, so they have to be cleared between them. Without this the suite
     * is order-dependent - one test spending a user's quota silently breaks the next, which is
     * exactly how it failed the first time it ran.
     *
     * <p>Cleared through Redis rather than by casting the bean to a concrete limiter: which
     * implementation is active depends on whether Redis is configured, and a test that only
     * works for one of them stops testing the one that ships.
     */
    @Autowired
    private StringRedisTemplate redis;

    /** See {@code AbstractApiIT}: cleanup must not run on a watermark that was never taken. */
    private static final long NOT_RECORDED = -1L;

    private long submissionWatermark = NOT_RECORDED;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        RedisTestContainer.registerProperties(registry);
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
        registry.add("arena.rate-limit.enabled", () -> "true");
        registry.add("arena.rate-limit.submissions-per-window", () -> String.valueOf(LIMIT));
        // Long enough that no token refills mid-test.
        registry.add("arena.rate-limit.window", () -> "10m");
    }

    @BeforeEach
    void resetLimiterAndRecordWatermark() {
        // Watermark first, before anything that can fail. Taking it after the Redis flush meant
        // a Redis hiccup left it at zero, and the @AfterEach below - which JUnit runs even when
        // setup throws - deleted every submission in the shared database.
        Long value = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM submissions", Long.class);
        submissionWatermark = value == null ? 0L : value;

        Set<String> buckets = redis.keys("ratelimit:*");
        if (buckets != null && !buckets.isEmpty()) {
            redis.delete(buckets);
        }
    }

    @AfterEach
    void undoWrites() {
        if (submissionWatermark == NOT_RECORDED) {
            return;
        }
        jdbcTemplate.update("DELETE FROM submissions WHERE id > ?", submissionWatermark);
    }

    private MvcResult submitAs(String username) throws Exception {
        return mockMvc.perform(post("/api/v1/submissions")
                        .with(user(username).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "problemSlug", "edit-distance",
                                "language", "JAVA",
                                "sourceCode", "class Main {}"))))
                .andReturn();
    }

    @Test
    @DisplayName("allows the configured burst, then answers 429 with Retry-After")
    void refusesBeyondTheLimit() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(submitAs("alice").getResponse().getStatus())
                    .as("submission %d of %d should be allowed", i + 1, LIMIT)
                    .isEqualTo(201);
        }

        mockMvc.perform(post("/api/v1/submissions")
                        .with(user("alice").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "problemSlug", "edit-distance",
                                "language", "JAVA",
                                "sourceCode", "class Main {}"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.type").value("https://codearena.dev/errors/rate-limited"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    @DisplayName("the limit is per user, not global")
    void limitIsPerUser() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            submitAs("bob");
        }
        assertThat(submitAs("bob").getResponse().getStatus()).isEqualTo(429);

        // carol has spent nothing and must be unaffected
        assertThat(submitAs("carol").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("remaining quota is advertised on successful responses")
    void advertisesRemainingQuota() throws Exception {
        MvcResult first = submitAs("carol");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("X-RateLimit-Remaining"))
                .isEqualTo(String.valueOf(LIMIT - 1));
    }

    @Test
    @DisplayName("a refused submission is not persisted")
    void refusedSubmissionIsNotStored() throws Exception {
        Long before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions s JOIN users u ON u.id = s.user_id WHERE u.username = 'alice'",
                Long.class);

        for (int i = 0; i < LIMIT; i++) {
            submitAs("alice");
        }
        assertThat(submitAs("alice").getResponse().getStatus()).isEqualTo(429);

        Long after = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submissions s JOIN users u ON u.id = s.user_id WHERE u.username = 'alice'",
                Long.class);

        assertThat(after).isEqualTo(before + LIMIT);
    }

    @Test
    @DisplayName("reads are never throttled")
    void readsAreNotLimited() throws Exception {
        for (int i = 0; i < LIMIT * 3; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .get("/api/v1/problems"))
                    .andExpect(status().isOk());
        }
    }
}
