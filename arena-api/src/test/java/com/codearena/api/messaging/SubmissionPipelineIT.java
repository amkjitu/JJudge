package com.codearena.api.messaging;

import com.codearena.api.support.KafkaTestContainer;
import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.ArenaTopics;
import com.codearena.common.event.SubmissionCreated;
import com.codearena.common.event.VerdictAssigned;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The pipeline against a real broker.
 *
 * <p>Both halves are exercised separately rather than by running the judge in-process: submitting
 * proves the API publishes what the worker expects, and publishing a verdict by hand proves the
 * API consumes and applies it. Between them they cover the wire format in both directions, which
 * is the part a mocked {@code KafkaTemplate} would skip entirely - and the format is exactly what
 * breaks when two modules are deployed independently.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Submission pipeline over Kafka")
class SubmissionPipelineIT {

    private static final long NOT_RECORDED = -1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long submissionWatermark = NOT_RECORDED;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        RedisTestContainer.registerProperties(registry);
        KafkaTestContainer.registerProperties(registry);
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
        registry.add("arena.rate-limit.enabled", () -> "false");
    }

    @BeforeEach
    void recordWatermark() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM submissions", Long.class);
        submissionWatermark = max == null ? 0L : max;
    }

    @AfterEach
    void undoWrites() {
        if (submissionWatermark == NOT_RECORDED) {
            return;
        }
        jdbcTemplate.update("DELETE FROM submissions WHERE id > ?", submissionWatermark);
        // Ratings and tag counters are derived from submissions, so rebuild both rather than
        // leaving this test's verdicts to leak into the recommendation fixtures.
        jdbcTemplate.update("DELETE FROM user_tag_stats");
        jdbcTemplate.update("""
                INSERT INTO user_tag_stats (user_id, tag_id, solved_count, attempt_count)
                SELECT a.user_id, a.tag_id,
                       COUNT(*) FILTER (WHERE a.solved) AS solved_count,
                       COUNT(*)                          AS attempt_count
                FROM (SELECT s.user_id, pt.tag_id, s.problem_id,
                             bool_or(s.verdict = 'AC') AS solved
                      FROM submissions s
                               JOIN problem_tags pt ON pt.problem_id = s.problem_id
                      GROUP BY s.user_id, pt.tag_id, s.problem_id) a
                GROUP BY a.user_id, a.tag_id
                """);
    }

    private long submitAs(String username, String slug) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "problemSlug", slug,
                "language", "JAVA",
                "sourceCode", "public class Main { public static void main(String[] a) {} }"));

        String response = mockMvc.perform(post("/api/v1/submissions")
                        .with(user(username).roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("id").asLong();
    }

    /** A consumer outside the application, so this reads what really reached the broker. */
    private KafkaConsumer<String, String> rawConsumer(String topic) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "pipeline-it-" + System.nanoTime());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    @Test
    @DisplayName("submitting publishes an event the judge can consume")
    void submissionIsPublished() throws Exception {
        try (KafkaConsumer<String, String> consumer = rawConsumer(ArenaTopics.SUBMISSIONS)) {
            consumer.poll(Duration.ofSeconds(1));   // force assignment before producing

            long submissionId = submitAs("alice", "edit-distance");

            // Find *this* submission's record rather than the first one on the topic. The
            // consumer joins a fresh group reading from the earliest offset, so it replays every
            // submission the other tests in this class published - and taking the head of the
            // batch asserts on whichever test happened to run first, which is a detail no test
            // should depend on.
            ConsumerRecord<String, String> record = await().atMost(Duration.ofSeconds(30))
                    .until(() -> {
                        for (ConsumerRecord<String, String> candidate
                                : consumer.poll(Duration.ofMillis(500))) {
                            if (String.valueOf(submissionId).equals(candidate.key())) {
                                return candidate;
                            }
                        }
                        return null;
                    }, r -> r != null);

            SubmissionCreated event = objectMapper.readValue(record.value(), SubmissionCreated.class);

            assertThat(event.submissionId()).isEqualTo(submissionId);
            assertThat(event.problemSlug()).isEqualTo("edit-distance");
            assertThat(event.sourceCode()).contains("class Main");
            // Limits travel with the work so the judge never has to query the API's database.
            assertThat(event.timeLimitMs()).isPositive();
            // Keyed by submission id, so every message about one submission lands on one
            // partition and is processed in order by a single consumer.
            assertThat(record.key()).isEqualTo(String.valueOf(submissionId));
        }
    }

    @Test
    @DisplayName("a published verdict is applied to the submission")
    void verdictIsConsumedAndApplied() throws Exception {
        long submissionId = submitAs("alice", "edit-distance");

        VerdictAssigned verdict = new VerdictAssigned(submissionId, null, null,
                Verdict.AC, 123, 20, 20, null, Instant.now());
        kafkaTemplate.send(ArenaTopics.VERDICTS, String.valueOf(submissionId), verdict).join();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT status, verdict, runtime_ms FROM submissions WHERE id = ?", submissionId);

            assertThat(row.get("status")).isEqualTo(SubmissionStatus.DONE.name());
            assertThat(row.get("verdict")).isEqualTo(Verdict.AC.name());
            assertThat(row.get("runtime_ms")).isEqualTo(123);
        });
    }

    @Test
    @DisplayName("the same verdict delivered twice is applied once")
    void duplicateVerdictIsIgnored() throws Exception {
        long submissionId = submitAs("alice", "edit-distance");
        Integer ratingBefore = jdbcTemplate.queryForObject(
                "SELECT rating FROM users WHERE username = 'alice'", Integer.class);

        VerdictAssigned verdict = new VerdictAssigned(submissionId, null, null,
                Verdict.AC, 50, 20, 20, null, Instant.now());

        // At-least-once delivery makes this inevitable in production, not hypothetical.
        kafkaTemplate.send(ArenaTopics.VERDICTS, String.valueOf(submissionId), verdict).join();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM submissions WHERE id = ?", String.class, submissionId))
                        .isEqualTo(SubmissionStatus.DONE.name()));

        Integer ratingAfterFirst = jdbcTemplate.queryForObject(
                "SELECT rating FROM users WHERE username = 'alice'", Integer.class);

        kafkaTemplate.send(ArenaTopics.VERDICTS, String.valueOf(submissionId), verdict).join();
        Thread.sleep(2_000);   // long enough for a second application to have happened

        assertThat(jdbcTemplate.queryForObject(
                "SELECT rating FROM users WHERE username = 'alice'", Integer.class))
                .isEqualTo(ratingAfterFirst);
        assertThat(ratingAfterFirst).isGreaterThan(ratingBefore);
    }

    @Test
    @DisplayName("a verdict for a submission that no longer exists is ignored, not fatal")
    void orphanVerdictDoesNotBreakTheConsumer() throws Exception {
        VerdictAssigned orphan = new VerdictAssigned(999_999L, null, null,
                Verdict.AC, 10, 20, 20, null, Instant.now());
        kafkaTemplate.send(ArenaTopics.VERDICTS, "999999", orphan).join();

        // The real assertion: the consumer survives and still processes the next message. A
        // listener that threw here would retry the orphan for ever and stall the partition.
        long submissionId = submitAs("alice", "edit-distance");
        kafkaTemplate.send(ArenaTopics.VERDICTS, String.valueOf(submissionId),
                new VerdictAssigned(submissionId, null, null, Verdict.WA, 77, 12, 20, 13,
                        Instant.now())).join();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT verdict FROM submissions WHERE id = ?", String.class, submissionId))
                        .isEqualTo(Verdict.WA.name()));
    }

    @Test
    @DisplayName("the SSE stream is offered for a queued submission")
    void streamEndpointIsAvailable() throws Exception {
        long submissionId = submitAs("alice", "edit-distance");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/submissions/{id}/stream", submissionId)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a stream opened after judging still delivers the verdict at once")
    void streamDeliversAVerdictThatAlreadyLanded() throws Exception {
        // Judging can finish in under two seconds, so a page that loads and then connects can
        // easily miss the event. Before the fix the emitter sat open for its full timeout with
        // nothing to say, and the browser only recovered via its poll fallback.
        long submissionId = submitAs("alice", "edit-distance");

        VerdictAssigned verdict = new VerdictAssigned(submissionId, null, null,
                Verdict.AC, 42, 20, 20, null, Instant.now());
        kafkaTemplate.send(ArenaTopics.VERDICTS, String.valueOf(submissionId), verdict).join();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM submissions WHERE id = ?", String.class, submissionId))
                        .isEqualTo(SubmissionStatus.DONE.name()));

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/submissions/{id}/stream", submissionId)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("verdict").contains("AC");
    }

    @Test
    @DisplayName("streaming an unknown submission is a 404 rather than a stream that never emits")
    void streamRejectsUnknownSubmission() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/submissions/{id}/stream", 999_999L)
                        .with(user("alice").roles("USER")))
                .andExpect(status().isNotFound());
    }
}
