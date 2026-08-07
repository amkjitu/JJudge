package com.codearena.api.repository;

import com.codearena.api.domain.Submission;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface SubmissionRepository extends JpaRepository<Submission, Long> {

    /**
     * Both associations are fetched because the response DTO flattens the problem slug/title
     * and the username onto the submission. Without the graph each row would trigger two
     * extra selects, and - with open-in-view disabled - they would fail outright once the
     * service transaction has closed.
     */
    @EntityGraph(attributePaths = {"user", "problem"})
    Optional<Submission> findWithDetailsById(Long id);

    @EntityGraph(attributePaths = {"user", "problem"})
    Page<Submission> findByUserIdOrderBySubmittedAtDesc(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "problem"})
    Page<Submission> findByProblemIdOrderBySubmittedAtDesc(Long problemId, Pageable pageable);

    long countByUserId(Long userId);

    long countByUserIdAndVerdict(Long userId, Verdict verdict);

    long countByUserIdAndStatus(Long userId, SubmissionStatus status);

    /**
     * Ids of every problem the user has ever had accepted. Returned as a Set because the
     * recommendation engine uses it purely for O(1) membership tests.
     */
    @Query("""
            SELECT DISTINCT s.problem.id FROM Submission s
            WHERE s.user.id = :userId AND s.verdict = :verdict
            """)
    Set<Long> findProblemIdsByUserIdAndVerdict(@Param("userId") Long userId,
                                               @Param("verdict") Verdict verdict);

    default Set<Long> findSolvedProblemIds(Long userId) {
        return findProblemIdsByUserIdAndVerdict(userId, Verdict.AC);
    }

    /**
     * How many times the user has attempted each problem, for the repetition-penalty term.
     * Each row is {@code [problemId, attemptCount]}.
     */
    @Query("""
            SELECT s.problem.id, COUNT(s) FROM Submission s
            WHERE s.user.id = :userId
            GROUP BY s.problem.id
            """)
    List<Object[]> countAttemptsPerProblem(@Param("userId") Long userId);

    boolean existsByUserIdAndProblemIdAndVerdict(Long userId, Long problemId, Verdict verdict);
}
