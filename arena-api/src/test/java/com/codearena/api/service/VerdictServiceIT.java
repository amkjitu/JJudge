package com.codearena.api.service;

import com.codearena.api.domain.Submission;
import com.codearena.api.domain.User;
import com.codearena.api.repository.ProblemRepository;
import com.codearena.api.repository.SubmissionRepository;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.repository.UserTagStatsRepository;
import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
import com.codearena.common.domain.Language;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.VerdictAssigned;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applying a verdict, against a real database.
 *
 * <p>Kafka is deliberately absent: this is about what happens to the rows once a verdict
 * arrives. {@code SubmissionPipelineIT} covers getting it there.
 */
@SpringBootTest
@DisplayName("VerdictService")
class VerdictServiceIT {

    private static final long PROBLEM_ID = 23L;   // edit-distance: tags dp, strings

    /**
     * alice, deliberately: she is the only demo user with no history at all on problem 23, so
     * every verdict applied here is genuinely a first attempt. carol has already solved it and
     * bob has already failed it twice, which would make "first solve" and "first attempt"
     * untestable against them.
     */
    private static final String SUBJECT = "alice";

    @Autowired
    private VerdictService verdictService;

    @Autowired
    private SubmissionRepository submissionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private UserTagStatsRepository userTagStatsRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long submissionWatermark;
    private int originalRating;
    private Long userId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        RedisTestContainer.registerProperties(registry);
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
        // No broker in this context. Without disabling listener startup the @KafkaListener
        // containers spin retrying a connection that will never succeed - seconds of log noise
        // per test class, and a slower shutdown for no benefit. SubmissionPipelineIT is the one
        // that actually wants a broker.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @BeforeEach
    void recordState() {
        Long max = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM submissions", Long.class);
        submissionWatermark = max == null ? 0L : max;

        User subject = userRepository.findByUsername(SUBJECT).orElseThrow();
        userId = subject.getId();
        originalRating = subject.getRating();
    }

    @AfterEach
    void restore() {
        jdbcTemplate.update("DELETE FROM submissions WHERE id > ?", submissionWatermark);
        jdbcTemplate.update("UPDATE users SET rating = ? WHERE id = ?", originalRating, userId);
        // user_tag_stats is derived, so rebuilding it from the surviving submissions is both a
        // restore and an assertion that the derivation still holds. The seed migration uses the
        // same statement.
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

    private Submission queuedSubmission() {
        return submissionRepository.saveAndFlush(Submission.builder()
                .user(userRepository.findById(userId).orElseThrow())
                .problem(problemRepository.findById(PROBLEM_ID).orElseThrow())
                .language(Language.JAVA)
                .status(SubmissionStatus.QUEUED)
                .build());
    }

    private VerdictAssigned verdictFor(Submission submission, Verdict verdict) {
        return new VerdictAssigned(submission.getId(), userId, PROBLEM_ID, verdict,
                145, verdict == Verdict.AC ? 20 : 12, 20,
                verdict == Verdict.AC ? null : 13, Instant.now());
    }

    private Map<String, int[]> statsByTag() {
        return userTagStatsRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(
                        stats -> stats.getTag().getName(),
                        stats -> new int[]{stats.getSolvedCount(), stats.getAttemptCount()},
                        (a, b) -> a));
    }

    @Test
    @DisplayName("writes the verdict onto the submission")
    void writesVerdict() {
        Submission submission = queuedSubmission();

        verdictService.apply(verdictFor(submission, Verdict.WA));

        Submission reloaded = submissionRepository.findById(submission.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubmissionStatus.DONE);
        assertThat(reloaded.getVerdict()).isEqualTo(Verdict.WA);
        assertThat(reloaded.getRuntimeMs()).isEqualTo(145);
    }

    @Test
    @DisplayName("a duplicate verdict changes nothing")
    void isIdempotent() {
        Submission submission = queuedSubmission();
        Map<String, int[]> before = statsByTag();

        verdictService.apply(verdictFor(submission, Verdict.AC));

        int ratingAfterFirst = userRepository.findById(userId).orElseThrow().getRating();
        Map<String, int[]> afterFirst = statsByTag();

        // At-least-once delivery means this will happen in production sooner or later. Without
        // the guard, a redelivered message would award the rating and the tag counters twice.
        Optional<Submission> second = verdictService.apply(verdictFor(submission, Verdict.AC));

        assertThat(second).isEmpty();
        assertThat(userRepository.findById(userId).orElseThrow().getRating())
                .isEqualTo(ratingAfterFirst);
        assertThat(statsByTag().get("dp")).isEqualTo(afterFirst.get("dp"));
        assertThat(before).isNotNull();
    }

    @Test
    @DisplayName("a first attempt increments attempts on every tag of the problem")
    void firstAttemptCountsAsAnAttempt() {
        Map<String, int[]> before = statsByTag();
        Submission submission = queuedSubmission();

        verdictService.apply(verdictFor(submission, Verdict.WA));

        Map<String, int[]> after = statsByTag();
        // edit-distance is tagged dp and strings; alice has history on both, so the rows
        // already exist and this exercises the UPDATE branch of the upsert.
        assertThat(after.get("dp")[1]).isEqualTo(before.get("dp")[1] + 1);
        assertThat(after.get("strings")[1]).isEqualTo(before.get("strings")[1] + 1);
        assertThat(after.get("dp")[0]).isEqualTo(before.get("dp")[0]);
    }

    @Test
    @DisplayName("a second failed attempt at the same problem does not count again")
    void repeatedAttemptsCountOnce() {
        verdictService.apply(verdictFor(queuedSubmission(), Verdict.WA));
        Map<String, int[]> afterFirst = statsByTag();

        verdictService.apply(verdictFor(queuedSubmission(), Verdict.TLE));

        // Counters are per problem, not per submission - otherwise proficiency would measure
        // persistence rather than skill.
        assertThat(statsByTag().get("dp")[1]).isEqualTo(afterFirst.get("dp")[1]);
    }

    @Test
    @DisplayName("a first solve increments solved and raises the rating")
    void firstSolveCountsAndAwardsRating() {
        Map<String, int[]> before = statsByTag();

        verdictService.apply(verdictFor(queuedSubmission(), Verdict.AC));

        assertThat(statsByTag().get("dp")[0]).isEqualTo(before.get("dp")[0] + 1);
        assertThat(userRepository.findById(userId).orElseThrow().getRating())
                .isGreaterThan(originalRating);
    }

    @Test
    @DisplayName("solving the same problem again awards nothing further")
    void resolvingIsNotProgress() {
        verdictService.apply(verdictFor(queuedSubmission(), Verdict.AC));
        int ratingAfterFirstSolve = userRepository.findById(userId).orElseThrow().getRating();
        Map<String, int[]> afterFirstSolve = statsByTag();

        verdictService.apply(verdictFor(queuedSubmission(), Verdict.AC));

        assertThat(userRepository.findById(userId).orElseThrow().getRating())
                .isEqualTo(ratingAfterFirstSolve);
        assertThat(statsByTag().get("dp")[0]).isEqualTo(afterFirstSolve.get("dp")[0]);
    }

    @Test
    @DisplayName("a failure costs no rating")
    void failureDoesNotPunish() {
        verdictService.apply(verdictFor(queuedSubmission(), Verdict.WA));

        // A practice platform that penalises attempting hard problems trains people to avoid
        // them, which is the opposite of the point.
        assertThat(userRepository.findById(userId).orElseThrow().getRating())
                .isEqualTo(originalRating);
    }

    @Test
    @DisplayName("a verdict for an unknown submission is ignored rather than fatal")
    void unknownSubmissionIsIgnored() {
        VerdictAssigned orphan = new VerdictAssigned(999_999L, userId, PROBLEM_ID,
                Verdict.AC, 10, 20, 20, null, Instant.now());

        assertThat(verdictService.apply(orphan)).isEmpty();
    }

    @Test
    @DisplayName("solving a harder problem is worth more rating than an easier one")
    void eloScalesWithDifficulty() {
        Function<Integer, Integer> gainFor = problemRating -> {
            jdbcTemplate.update("UPDATE users SET rating = ? WHERE id = ?", originalRating, userId);
            jdbcTemplate.update("UPDATE problems SET rating = ? WHERE id = ?", problemRating, PROBLEM_ID);
            Submission submission = queuedSubmission();
            verdictService.apply(verdictFor(submission, Verdict.AC));
            int gained = userRepository.findById(userId).orElseThrow().getRating() - originalRating;
            jdbcTemplate.update("DELETE FROM submissions WHERE id = ?", submission.getId());
            return gained;
        };

        int easyGain = gainFor.apply(originalRating - 400);
        int hardGain = gainFor.apply(originalRating + 400);

        jdbcTemplate.update("UPDATE problems SET rating = 1500 WHERE id = ?", PROBLEM_ID);
        assertThat(hardGain).isGreaterThan(easyGain);
    }
}
