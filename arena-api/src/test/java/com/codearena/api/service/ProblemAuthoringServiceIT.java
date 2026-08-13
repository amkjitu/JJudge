package com.codearena.api.service;

import com.codearena.api.mongo.ProblemStatement;
import com.codearena.api.mongo.ProblemStatementRepository;
import com.codearena.api.mongo.ProblemTestCaseRepository;
import com.codearena.api.mongo.ProblemTestCases;
import com.codearena.api.support.MongoTestContainer;
import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Problem authoring against a real MongoDB.
 *
 * <p>The admin editors are the reason this exists. Before them, a problem the judge could
 * actually run had to be added by editing a JSON file in the repository and restarting — a
 * problem created through the UI got a title, a rating, and a verdict derived from a hash.
 *
 * <p>Slice tests already cover the forms. What only a real datastore can show is that a save
 * survives, that the cross-check between a statement and its cases sees both documents, and that
 * deleting a problem does not leave its prose behind for the next problem to inherit.
 */
@SpringBootTest
@DisplayName("Problem authoring")
class ProblemAuthoringServiceIT {

    private static final String SLUG = "authoring-it-problem";

    @Autowired
    private ProblemAuthoringService service;

    @Autowired
    private ProblemStatementRepository statements;

    @Autowired
    private ProblemTestCaseRepository testCases;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        RedisTestContainer.registerProperties(registry);
        MongoTestContainer.registerProperties(registry);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        // The whole application context comes up, so the security beans need a signing key even
        // though nothing here issues a token.
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
    }

    @AfterEach
    void cleanUp() {
        statements.deleteById(SLUG);
        testCases.deleteById(SLUG);
    }

    private void givenExample(String input, String output) {
        service.saveStatement(SLUG, "A statement.", null,
                List.of(new ProblemStatement.WorkedExample(input, output, null)));
    }

    private void givenSample(String input, String output) {
        service.saveTestCases(SLUG, List.of(new ProblemTestCases.Case(1, input, output, true)));
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrip {

        @Test
        @DisplayName("a statement written here is the one the problem page reads back")
        void statementSurvives() {
            service.saveStatement(SLUG, "Find two numbers.", "Use a hash map.",
                    List.of(new ProblemStatement.WorkedExample("4 9", "0 1", "2 + 7 = 9")));

            ProblemStatement saved = service.findStatement(SLUG).orElseThrow();
            assertThat(saved.getStatementMarkdown()).isEqualTo("Find two numbers.");
            assertThat(saved.getEditorialMarkdown()).isEqualTo("Use a hash map.");
            assertThat(saved.getExamples()).singleElement().satisfies(e -> {
                assertThat(e.input()).isEqualTo("4 9");
                assertThat(e.output()).isEqualTo("0 1");
            });
        }

        @Test
        @DisplayName("test cases keep their order, their sample flags and their exact text")
        void testCasesSurvive() {
            // Whitespace is the point. Expected output is compared against a program's real
            // stdout, so a store that trimmed or normalised it would fail correct submissions
            // in a way nothing in the judge could explain.
            service.saveTestCases(SLUG, List.of(
                    new ProblemTestCases.Case(1, "4 9\n2 7 11 15", "0 1", true),
                    new ProblemTestCases.Case(2, "  leading", "  spaced  ", false)));

            List<ProblemTestCases.Case> saved = service.findTestCases(SLUG).orElseThrow().getCases();
            assertThat(saved).extracting(ProblemTestCases.Case::index).containsExactly(1, 2);
            assertThat(saved.get(0).input()).isEqualTo("4 9\n2 7 11 15");
            assertThat(saved.get(0).sample()).isTrue();
            assertThat(saved.get(1).expectedOutput()).isEqualTo("  spaced  ");
        }

        @Test
        @DisplayName("saving again replaces rather than accumulating")
        void savingAgainReplaces() {
            service.saveTestCases(SLUG, List.of(
                    new ProblemTestCases.Case(1, "a", "1", true),
                    new ProblemTestCases.Case(2, "b", "2", false)));
            service.saveTestCases(SLUG, List.of(new ProblemTestCases.Case(1, "a", "1", true)));

            assertThat(service.findTestCases(SLUG).orElseThrow().getCases()).hasSize(1);
        }

        @Test
        @DisplayName("deleting a problem takes its prose and its cases with it")
        void deleteRemovesBoth() {
            // MongoDB knows nothing about the PostgreSQL foreign key that cascades, so without
            // an explicit delete a new problem later given this slug inherits both documents -
            // and is judged against test cases nobody wrote for it.
            givenExample("in", "out");
            givenSample("in", "out");

            service.deleteAuthoring(SLUG);

            assertThat(service.findStatement(SLUG)).isEmpty();
            assertThat(service.findTestCases(SLUG)).isEmpty();
        }
    }

    @Nested
    @DisplayName("authoring status")
    class Status {

        @Test
        @DisplayName("a problem with no cases is reported as simulated, not ready")
        void noCasesMeansSimulated() {
            givenExample("in", "out");

            ProblemAuthoringService.AuthoringStatus status = service.statusOf(SLUG);
            assertThat(status.hasStatement()).isTrue();
            assertThat(status.judgedForReal()).isFalse();
            assertThat(status.isComplete()).isFalse();
        }

        @Test
        @DisplayName("a statement and matching cases are reported as ready")
        void matchingIsReady() {
            givenExample("in", "out");
            givenSample("in", "out");

            ProblemAuthoringService.AuthoringStatus status = service.statusOf(SLUG);
            assertThat(status.judgedForReal()).isTrue();
            assertThat(status.sampleCount()).isEqualTo(1);
            assertThat(status.drift()).isEmpty();
            assertThat(status.isComplete()).isTrue();
        }

        @Test
        @DisplayName("an example no sample case runs is reported")
        void exampleWithNoMatchingCaseIsReported() {
            givenExample("shown to the reader", "42");
            givenSample("something else entirely", "42");

            assertThat(service.statusOf(SLUG).drift())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("displayed but never run");
        }

        @Test
        @DisplayName("an example whose output disagrees with its sample case is reported")
        void mismatchedOutputIsReported() {
            // The worst version of this: the reader is shown one answer and marked against
            // another, so a correct submission is rejected and looks like the author's mistake.
            givenExample("same input", "what the reader is shown");
            givenSample("same input", "what is actually judged");

            assertThat(service.statusOf(SLUG).drift())
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("what the reader is shown")
                    .contains("what is actually judged");
        }

        @Test
        @DisplayName("a non-sample case matching an example does not count")
        void onlySamplesSatisfyAnExample() {
            // The case exists and is judged, but is not shown - so the example on the problem
            // page is still text nobody's code is ever run against.
            givenExample("in", "out");
            service.saveTestCases(SLUG, List.of(new ProblemTestCases.Case(1, "in", "out", false)));

            assertThat(service.statusOf(SLUG).drift()).isNotEmpty();
        }

        @Test
        @DisplayName("the batch lookup agrees with the single one")
        void batchMatchesSingle() {
            // The admin list uses the batch form for the whole page. If the two disagreed, the
            // list would show a green row for a problem whose own page reports a mismatch.
            givenExample("in", "out");
            givenSample("in", "out");

            assertThat(service.statusOf(List.of(SLUG)).get(SLUG))
                    .isEqualTo(service.statusOf(SLUG));
        }

        @Test
        @DisplayName("a slug with nothing written reports as empty rather than failing")
        void unknownSlugIsEmptyNotAnError() {
            ProblemAuthoringService.AuthoringStatus status = service.statusOf("no-such-problem");

            assertThat(status.hasStatement()).isFalse();
            assertThat(status.caseCount()).isZero();
            assertThat(status.drift()).isEmpty();
        }
    }
}
