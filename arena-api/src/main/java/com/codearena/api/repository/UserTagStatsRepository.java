package com.codearena.api.repository;

import com.codearena.api.domain.UserTagStats;
import com.codearena.api.domain.UserTagStatsId;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserTagStatsRepository extends JpaRepository<UserTagStats, UserTagStatsId> {

    @EntityGraph(attributePaths = "tag")
    List<UserTagStats> findByUserId(Long userId);

    long countByUserId(Long userId);

    /**
     * Adds the given deltas to every tag of a problem, creating rows that do not exist yet.
     *
     * <p>Native, because this is an upsert and JPQL has no vocabulary for {@code ON CONFLICT}.
     * The alternative - select, branch, insert or update, once per tag - is three round trips per
     * tag and a lost-update race between two verdicts landing for the same user at once. One
     * statement covers every tag of the problem and lets PostgreSQL resolve the conflict under
     * the row lock it already holds.
     *
     * <p>{@code clearAutomatically} because this bypasses the persistence context: without it a
     * {@code UserTagStats} already loaded in this transaction would keep its stale counters.
     *
     * <h2>Why the proposed row carries {@code GREATEST}, not the raw deltas</h2>
     *
     * <p>PostgreSQL evaluates CHECK constraints on the row an {@code INSERT} <em>proposes</em>,
     * before it detects the conflict and switches to {@code DO UPDATE}. So the proposal must
     * satisfy {@code attempt_count >= solved_count} on its own, even when the branch that would
     * have violated it never runs. Solving a problem that was already attempted sends deltas of
     * {@code (solved 1, attempt 0)}, and the literal proposal {@code (1, 0)} is rejected outright
     * - the update to an existing perfectly legal row never gets a chance. That failed every
     * accepted resubmission in the pipeline, with a constraint error naming counts that were
     * never going to be stored.
     *
     * <p>{@code GREATEST} makes the proposal self-consistent: with no prior row, a solve is also
     * the first attempt we have evidence of, so it counts as one. The update branch reads the
     * parameters directly rather than {@code EXCLUDED}, so the widened attempt count does not
     * leak into rows that already have real history.
     *
     * <p>The same {@code GREATEST} guards the update branch for the one case that can otherwise
     * break the invariant: a tag added to a problem after a user had already attempted it, whose
     * earlier attempts were never counted against that tag. Recording the solve as an attempt too
     * is the only reading that stays true - nobody solves more than they attempt.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_tag_stats (user_id, tag_id, solved_count, attempt_count)
            SELECT :userId, pt.tag_id, :solvedDelta, GREATEST(:attemptDelta, :solvedDelta)
            FROM problem_tags pt
            WHERE pt.problem_id = :problemId
            ON CONFLICT (user_id, tag_id) DO UPDATE
            SET solved_count  = user_tag_stats.solved_count + :solvedDelta,
                attempt_count = GREATEST(user_tag_stats.attempt_count + :attemptDelta,
                                         user_tag_stats.solved_count  + :solvedDelta)
            """, nativeQuery = true)
    int applyDeltas(@Param("userId") Long userId,
                    @Param("problemId") Long problemId,
                    @Param("solvedDelta") int solvedDelta,
                    @Param("attemptDelta") int attemptDelta);
}
