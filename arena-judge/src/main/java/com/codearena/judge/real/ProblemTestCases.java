package com.codearena.judge.real;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Comparator;
import java.util.List;

/**
 * A problem's test cases, as the judge reads them.
 *
 * <p>Deliberately a second declaration of the same collection arena-api writes, rather than a
 * shared class. The two services deploy independently, and a shared document type makes every
 * field change in one a compile-time break in the other - which is the coupling that makes
 * "independently deployable" untrue in practice. The event contracts in arena-common are shared
 * because a producer and a consumer must agree on a wire format; a collection both happen to read
 * is a weaker relationship, and this side only needs three fields of it.
 *
 * <p>Read-only here. arena-api owns seeding; the judge never writes.
 */
@Document(collection = "problem_test_cases")
public class ProblemTestCases {

    @Id
    private String slug;

    @Field("cases")
    private List<Case> cases;

    public String getSlug() {
        return slug;
    }

    /** Ordered, so "failed on case 4" means the same case on every run. */
    public List<JudgeTestCase> toJudgeCases() {
        if (cases == null) {
            return List.of();
        }
        return cases.stream()
                .sorted(Comparator.comparingInt(Case::index))
                .map(c -> new JudgeTestCase(c.index(), c.input(), c.expectedOutput()))
                .toList();
    }

    /** The stored shape. {@code sample} is not read here - the judge runs every case. */
    public record Case(int index, String input, String expectedOutput, boolean sample) {
    }
}
