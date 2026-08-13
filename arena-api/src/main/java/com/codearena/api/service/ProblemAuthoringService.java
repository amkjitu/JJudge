package com.codearena.api.service;

import com.codearena.api.mongo.ProblemStatement;
import com.codearena.api.mongo.ProblemStatementRepository;
import com.codearena.api.mongo.ProblemTestCaseRepository;
import com.codearena.api.mongo.ProblemTestCases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Reads and writes the two MongoDB documents that make a problem judgeable: its statement and
 * its test cases.
 *
 * <p>Separate from {@link ProblemService}, which owns the relational side. A problem's metadata
 * lives in PostgreSQL with foreign keys and a rating; its prose and test data live in MongoDB
 * because they are documents with no shape worth normalising. Keeping the two services apart
 * keeps that split visible rather than hiding a second datastore behind the first.
 */
@Service
public class ProblemAuthoringService {

    private static final Logger log = LoggerFactory.getLogger(ProblemAuthoringService.class);

    private final ProblemStatementRepository statements;
    private final ProblemTestCaseRepository testCases;

    public ProblemAuthoringService(ProblemStatementRepository statements,
                                   ProblemTestCaseRepository testCases) {
        this.statements = statements;
        this.testCases = testCases;
    }

    public Optional<ProblemStatement> findStatement(String slug) {
        return statements.findById(slug);
    }

    public Optional<ProblemTestCases> findTestCases(String slug) {
        return testCases.findById(slug);
    }

    public void saveStatement(String slug, String statementMarkdown, String editorialMarkdown,
                              List<ProblemStatement.WorkedExample> examples) {
        statements.save(new ProblemStatement(slug, statementMarkdown,
                blankToNull(editorialMarkdown), examples));
        log.info("Saved statement for '{}' with {} worked example(s)", slug, examples.size());
    }

    public void saveTestCases(String slug, List<ProblemTestCases.Case> cases) {
        testCases.save(new ProblemTestCases(slug, cases));
        long samples = cases.stream().filter(ProblemTestCases.Case::sample).count();
        log.info("Saved {} test case(s) for '{}', {} of them samples",
                cases.size(), slug, samples);
    }

    public void deleteAuthoring(String slug) {
        statements.deleteById(slug);
        testCases.deleteById(slug);
    }

    /**
     * How a problem stands: whether it can be judged for real, and whether what a reader is shown
     * matches what their code is run against.
     */
    public AuthoringStatus statusOf(String slug) {
        Optional<ProblemStatement> statement = findStatement(slug);
        Optional<ProblemTestCases> cases = findTestCases(slug);

        int caseCount = cases.map(c -> c.getCases().size()).orElse(0);
        int sampleCount = cases.map(c -> c.getSamples().size()).orElse(0);
        List<String> drift = drift(statement.orElse(null), cases.orElse(null));

        return new AuthoringStatus(statement.isPresent(), caseCount, sampleCount, drift);
    }

    /** {@link #statusOf} for many slugs without a round trip each — the admin list needs all of them. */
    public Map<String, AuthoringStatus> statusOf(List<String> slugs) {
        Map<String, ProblemStatement> byStatement = statements.findAllById(slugs).stream()
                .collect(Collectors.toMap(ProblemStatement::getSlug, s -> s));
        Map<String, ProblemTestCases> byCases = testCases.findAllById(slugs).stream()
                .collect(Collectors.toMap(ProblemTestCases::getSlug, c -> c));

        return slugs.stream().collect(Collectors.toMap(slug -> slug, slug -> {
            ProblemStatement statement = byStatement.get(slug);
            ProblemTestCases cases = byCases.get(slug);
            return new AuthoringStatus(
                    statement != null,
                    cases == null ? 0 : cases.getCases().size(),
                    cases == null ? 0 : cases.getSamples().size(),
                    drift(statement, cases));
        }));
    }

    /**
     * Every worked example must appear among the sample cases, with the same expected output.
     *
     * <p>This is the same invariant {@code tools/generate_test_cases.py} enforces at build time,
     * and it is here for the same reason: the statement is what a person reads and the cases are
     * what their code is marked against, so when the two drift the platform shows someone one
     * thing and judges them on another. That is a bug nobody reports, because it looks like the
     * user's own mistake.
     *
     * <p>Reported rather than refused. Blocking a statement save until the matching test case
     * exists would make the two forms impossible to fill in in either order, so the warning
     * follows the editor around instead — on the list, on both forms — until it is resolved.
     */
    private List<String> drift(ProblemStatement statement, ProblemTestCases cases) {
        if (statement == null || statement.getExamples().isEmpty()) {
            return List.of();
        }
        Map<String, String> samples = cases == null ? Map.of()
                : cases.getSamples().stream().collect(Collectors.toMap(
                        ProblemTestCases.Case::input,
                        ProblemTestCases.Case::expectedOutput,
                        // Two sample cases with identical input are themselves a mistake, but not
                        // this one's to report - keep the first and let the comparison proceed.
                        (first, second) -> first));

        return statement.getExamples().stream()
                .map(example -> {
                    String judged = samples.get(example.input());
                    if (judged == null) {
                        return "an example shown in the statement is not among the sample cases, "
                                + "so it is displayed but never run";
                    }
                    if (!judged.equals(example.output())) {
                        return "an example shows output \"" + abbreviate(example.output())
                                + "\" but the matching sample case expects \""
                                + abbreviate(judged) + "\"";
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String abbreviate(String value) {
        String flat = value.replace("\n", "\\n");
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "...";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @param hasStatement whether a reader is shown anything at all
     * @param caseCount    how many cases a submission is run against; zero means the judge falls
     *                     back to a simulated verdict
     * @param sampleCount  how many of those are shown on the problem page
     * @param drift        human-readable complaints where the statement and the cases disagree
     */
    public record AuthoringStatus(boolean hasStatement, int caseCount, int sampleCount,
                                  List<String> drift) {

        public AuthoringStatus {
            drift = List.copyOf(drift);
        }

        /** Whether a submission to this problem is executed rather than simulated. */
        public boolean judgedForReal() {
            return caseCount > 0;
        }

        public boolean isComplete() {
            return hasStatement && judgedForReal() && sampleCount > 0 && drift.isEmpty();
        }
    }
}
