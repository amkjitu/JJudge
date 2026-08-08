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

import java.util.Collection;
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

    @EntityGraph(attributePaths = {"user", "problem"})
    List<Submission> findTop10ByUserIdAndProblemIdOrderBySubmittedAtDesc(Long userId, Long problemId);

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

    /**
     * Distinct problems solved, for several users at once. Two cheap queries beat one clever
     * one here: the alternative is an outer join from User to Submission with a condition on
     * the join, which Hibernate will express but which reads far worse than this.
     * Each row is {@code [userId, solvedCount]}.
     */
    @Query("""
            SELECT s.user.id, COUNT(DISTINCT s.problem.id) FROM Submission s
            WHERE s.verdict = :verdict AND s.user.id IN :userIds
            GROUP BY s.user.id
            """)
    List<Object[]> countSolvedForUsers(@Param("userIds") Collection<Long> userIds,
                                       @Param("verdict") Verdict verdict);

    /**
     * Every accepted submission for a user in chronological order, as
     * {@code [problemId, submittedAt]}.
     *
     * <p>Returned raw rather than aggregated in SQL because the interesting series is
     * "cumulative <em>distinct</em> problems over time", and the first accepted submission per
     * problem is what counts - a window function would express it, but the dataset per user is
     * tens of rows and folding it in Java stays readable and testable without a database.
     */
    @Query("""
            SELECT s.problem.id, s.submittedAt FROM Submission s
            WHERE s.user.id = :userId AND s.verdict = :verdict
            ORDER BY s.submittedAt ASC
            """)
    List<Object[]> findAcceptedTimeline(@Param("userId") Long userId,
                                        @Param("verdict") Verdict verdict);
}
