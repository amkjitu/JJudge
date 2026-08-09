package com.codearena.api.repository;

import com.codearena.api.domain.Problem;
import com.codearena.common.domain.Difficulty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProblemRepository extends JpaRepository<Problem, Long>, JpaSpecificationExecutor<Problem> {

    /**
     * {@code @EntityGraph} rather than a JOIN FETCH so the same method still paginates
     * correctly - Hibernate applies the graph as a second batched select instead of trying
     * to limit a row-multiplied join.
     */
    @EntityGraph(attributePaths = "tags")
    Optional<Problem> findBySlug(String slug);

    @EntityGraph(attributePaths = "tags")
    Page<Problem> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = "tags")
    Page<Problem> findByDifficulty(Difficulty difficulty, Pageable pageable);

    boolean existsBySlug(String slug);

    /**
     * Candidate pool for the recommendation engine: everything inside the rating band that
     * the user has not already solved. Excluding solved problems in SQL rather than in Java
     * keeps the pool small before it ever reaches the scoring loop.
     */
    @EntityGraph(attributePaths = "tags")
    @Query("""
            SELECT p FROM Problem p
            WHERE p.rating BETWEEN :minRating AND :maxRating
              AND p.id NOT IN :solvedProblemIds
            """)
    List<Problem> findCandidates(@Param("minRating") int minRating,
                                 @Param("maxRating") int maxRating,
                                 @Param("solvedProblemIds") Collection<Long> solvedProblemIds);

    /**
     * Guards the {@code NOT IN} against an empty collection.
     *
     * <p>A user who has solved nothing is the common case for a new account, and
     * {@code NOT IN ()} is a syntax error in PostgreSQL. Hibernate papers over it, but relying
     * on that is relying on an implementation detail of the dialect - passing an id that cannot
     * exist keeps the generated SQL valid and the intent obvious.
     */
    default List<Problem> findCandidatesExcludingSolved(int minRating,
                                                        int maxRating,
                                                        Collection<Long> solvedProblemIds) {
        Collection<Long> exclusions = solvedProblemIds.isEmpty() ? List.of(-1L) : solvedProblemIds;
        return findCandidates(minRating, maxRating, exclusions);
    }

    @EntityGraph(attributePaths = "tags")
    List<Problem> findAllByIdIn(Collection<Long> ids);
}
