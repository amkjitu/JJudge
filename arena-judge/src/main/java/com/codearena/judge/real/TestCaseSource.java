package com.codearena.judge.real;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.util.List;

/**
 * Supplies a problem's test cases to the judge.
 *
 * <p>A thin seam over the repository so the engine's tests can hand it cases directly instead of
 * standing up MongoDB to assert something about output comparison.
 */
public class TestCaseSource {

    private static final Logger log = LoggerFactory.getLogger(TestCaseSource.class);

    private final ProblemTestCaseRepository repository;

    public TestCaseSource(ProblemTestCaseRepository repository) {
        this.repository = repository;
    }

    /**
     * @return the problem's cases in order, or empty when it has none or MongoDB is unreachable.
     *         Both are the same answer to the caller - this judge cannot execute the submission -
     *         and it degrades to simulating rather than leaving the submission unjudged.
     */
    public List<JudgeTestCase> findFor(String problemSlug) {
        try {
            return repository.findById(problemSlug)
                    .map(ProblemTestCases::toJudgeCases)
                    .orElse(List.of());
        } catch (DataAccessException e) {
            log.warn("Could not read test cases for '{}': {}", problemSlug, e.getMessage());
            return List.of();
        }
    }
}
