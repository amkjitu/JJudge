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
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_tag_stats (user_id, tag_id, solved_count, attempt_count)
            SELECT :userId, pt.tag_id, :solvedDelta, :attemptDelta
            FROM problem_tags pt
            WHERE pt.problem_id = :problemId
            ON CONFLICT (user_id, tag_id) DO UPDATE
            SET solved_count  = user_tag_stats.solved_count + EXCLUDED.solved_count,
                attempt_count = user_tag_stats.attempt_count + EXCLUDED.attempt_count
            """, nativeQuery = true)
    int applyDeltas(@Param("userId") Long userId,
                    @Param("problemId") Long problemId,
                    @Param("solvedDelta") int solvedDelta,
                    @Param("attemptDelta") int attemptDelta);
}
