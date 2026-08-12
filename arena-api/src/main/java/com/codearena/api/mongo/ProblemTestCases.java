package com.codearena.api.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Comparator;
import java.util.List;

/**
 * The test cases one problem is judged against.
 *
 * <h2>Why the whole set is one document</h2>
 *
 * <p>Cases are never queried individually, filtered, or read except as a complete set by a judge
 * about to run a submission. Storing them as one document per problem means judging is a single
 * lookup by primary key rather than a query returning N rows, and it makes "the cases for this
 * problem" an atomic thing to replace - a half-updated test set is the kind of state that fails
 * correct submissions for reasons nobody can reproduce.
 *
 * <p>Keyed by slug, matching {@link ProblemStatement}. The two are deliberately separate: a
 * statement is prose for a human, a test case is data for a process, and they change for
 * different reasons.
 *
 * <h2>Samples are not a separate collection</h2>
 *
 * <p>{@code sample} marks the cases a reader is allowed to see. They are judged like any other -
 * a sample that is not actually run is a sample that can silently stop matching the statement.
 * Everything else stays server-side: publishing hidden inputs would let someone hard-code the
 * answers instead of solving the problem.
 */
@Document(collection = ProblemTestCases.COLLECTION)
public class ProblemTestCases {

    public static final String COLLECTION = "problem_test_cases";

    @Id
    private String slug;

    @Field("cases")
    private List<Case> cases;

    protected ProblemTestCases() {
        // Spring Data materialises documents reflectively.
    }

    public ProblemTestCases(String slug, List<Case> cases) {
        this.slug = slug;
        this.cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public String getSlug() {
        return slug;
    }

    /** Every case, ordered, so a "first failing case" is the same case on every run. */
    public List<Case> getCases() {
        return cases == null ? List.of()
                : cases.stream().sorted(Comparator.comparingInt(Case::index)).toList();
    }

    /** Just the cases a reader may see. */
    public List<Case> getSamples() {
        return getCases().stream().filter(Case::sample).toList();
    }

    /**
     * One case.
     *
     * @param index          1-based position, and the number reported as the failing case
     * @param input          fed to the submission on stdin, verbatim
     * @param expectedOutput what stdout must equal, after trailing whitespace is normalised
     * @param sample         whether a reader may see this one
     */
    public record Case(int index, String input, String expectedOutput, boolean sample) {
    }
}
