package com.codearena.api.repository;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.User;
import com.codearena.api.domain.UserTagStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The tag-proficiency counters the recommendation engine reads.
 *
 * <p>These run against a real PostgreSQL rather than a mock because the behaviour under test is
 * PostgreSQL's: which row an upsert proposes, when the CHECK constraint is evaluated against it,
 * and what {@code ON CONFLICT DO UPDATE} does with the result. A mocked repository would assert
 * that a method was called and would have been perfectly green while the pipeline was broken.
 */
@DisplayName("UserTagStatsRepository counter upsert")
class UserTagStatsRepositoryIT extends AbstractRepositoryIT {

    /** dp and binary-search - one tag alice has failed at, one she has partly solved. */
    private static final String ATTEMPTED_PROBLEM = "longest-increasing-subsequence";

    @Autowired
    private UserTagStatsRepository userTagStatsRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProblemRepository problemRepository;

    private Long aliceId;
    private Long adminId;
    private Long problemId;

    @BeforeEach
    void resolveFixtures() {
        aliceId = userRepository.findByUsername("alice").map(User::getId).orElseThrow();
        adminId = userRepository.findByUsername("admin").map(User::getId).orElseThrow();
        problemId = problemRepository.findBySlug(ATTEMPTED_PROBLEM).map(Problem::getId).orElseThrow();
    }

    @Test
    @DisplayName("solving a problem that was already attempted records the solve without a new attempt")
    void solveOnAnExistingRow() {
        // The regression. Deltas of (solved 1, attempt 0) make the upsert propose a literal
        // (1, 0), which violates `attempt_count >= solved_count` - and PostgreSQL rejects the
        // proposal before it ever notices the conflict, so the legal update never runs. Every
        // accepted resubmission in the pipeline died here, and the constraint error named counts
        // that were never going to be stored.
        Map<String, UserTagStats> before = statsByTag(aliceId);
        assertThat(before).containsKeys("dp", "binary-search");

        assertThatCode(() -> userTagStatsRepository.applyDeltas(aliceId, problemId, 1, 0))
                .doesNotThrowAnyException();

        Map<String, UserTagStats> after = statsByTag(aliceId);
        assertThat(after.get("dp").getSolvedCount())
                .isEqualTo(before.get("dp").getSolvedCount() + 1);
        assertThat(after.get("dp").getAttemptCount())
                .as("the attempt was counted when it was first made, not again on the solve")
                .isEqualTo(before.get("dp").getAttemptCount());
        assertThat(after.get("binary-search").getSolvedCount())
                .isEqualTo(before.get("binary-search").getSolvedCount() + 1);
        assertThat(after.get("binary-search").getAttemptCount())
                .isEqualTo(before.get("binary-search").getAttemptCount());
    }

    @Test
    @DisplayName("a first-ever solve creates rows counting one attempt alongside it")
    void solveWithNoExistingRow() {
        // admin has no counters at all, so both tags take the INSERT branch. Nobody solves more
        // than they attempt: with no prior row, the solve is the first attempt on record.
        assertThat(statsByTag(adminId)).isEmpty();

        userTagStatsRepository.applyDeltas(adminId, problemId, 1, 0);

        assertThat(statsByTag(adminId)).hasSize(2)
                .allSatisfy((tag, stats) -> {
                    assertThat(stats.getSolvedCount()).isEqualTo(1);
                    assertThat(stats.getAttemptCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("a failed first attempt counts an attempt and no solve")
    void firstAttemptWithoutSolve() {
        userTagStatsRepository.applyDeltas(adminId, problemId, 0, 1);

        assertThat(statsByTag(adminId)).hasSize(2)
                .allSatisfy((tag, stats) -> {
                    assertThat(stats.getSolvedCount()).isZero();
                    assertThat(stats.getAttemptCount()).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("counters never claim more solves than attempts")
    void invariantHoldsWhenATagIsAddedAfterTheAttempts() {
        // The case that can otherwise break the invariant on an existing row: a tag attached to
        // a problem after a user had already attempted it, so their earlier attempts were never
        // counted against it. admin's row here starts at 1 solved of 1 attempted.
        userTagStatsRepository.applyDeltas(adminId, problemId, 1, 0);

        userTagStatsRepository.applyDeltas(adminId, problemId, 1, 0);

        assertThat(statsByTag(adminId)).hasSize(2)
                .allSatisfy((tag, stats) -> {
                    assertThat(stats.getSolvedCount()).isEqualTo(2);
                    assertThat(stats.getAttemptCount())
                            .as("the solve is itself an attempt, so the count widens to match")
                            .isEqualTo(2);
                });
    }

    @Test
    @DisplayName("does nothing for a problem with no tags rather than failing")
    void unknownProblemIsANoOp() {
        assertThat(userTagStatsRepository.applyDeltas(aliceId, -1L, 1, 1)).isZero();
    }

    private Map<String, UserTagStats> statsByTag(Long userId) {
        return userTagStatsRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(stats -> stats.getTag().getName(), Function.identity()));
    }
}
