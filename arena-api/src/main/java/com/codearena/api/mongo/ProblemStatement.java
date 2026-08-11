package com.codearena.api.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.List;

/**
 * The prose half of a problem: statement, worked examples and editorial.
 *
 * <h2>Why this is not a column on {@code problems}</h2>
 *
 * <p>The relational side of a problem is small, uniform and constantly queried - id, slug,
 * rating, difficulty, tags - and every listing, filter and recommendation pass reads it.
 * The prose is the opposite: kilobytes of Markdown, read only when one problem is opened, and
 * genuinely variable in shape. Some problems have three worked examples, some none; an editorial
 * may or may not exist. Modelling that relationally means either nullable columns nobody fills
 * or a join table per optional section.
 *
 * <p>Splitting it keeps the hot table narrow. That is the actual argument for a document store
 * here - not "MongoDB is faster", which for this data it is not, but that the two halves have
 * different shapes and wildly different read patterns.
 *
 * <p>The slug is the {@code _id}: it is already the public identifier of a problem, unique in
 * PostgreSQL, and the value the detail page has in hand. Using the numeric id would mean this
 * collection needed re-keying if problems were ever reimported.
 */
@Document(collection = ProblemStatement.COLLECTION)
public class ProblemStatement {

    public static final String COLLECTION = "problem_statements";

    @Id
    private String slug;

    @Field("statement_markdown")
    private String statementMarkdown;

    @Field("editorial_markdown")
    private String editorialMarkdown;

    @Field("examples")
    private List<WorkedExample> examples;

    protected ProblemStatement() {
        // Spring Data materialises documents reflectively.
    }

    public ProblemStatement(String slug, String statementMarkdown, String editorialMarkdown,
                            List<WorkedExample> examples) {
        this.slug = slug;
        this.statementMarkdown = statementMarkdown;
        this.editorialMarkdown = editorialMarkdown;
        this.examples = examples == null ? List.of() : List.copyOf(examples);
    }

    public String getSlug() {
        return slug;
    }

    public String getStatementMarkdown() {
        return statementMarkdown;
    }

    public String getEditorialMarkdown() {
        return editorialMarkdown;
    }

    public List<WorkedExample> getExamples() {
        return examples == null ? List.of() : examples;
    }

    /**
     * One sample input/output pair. Embedded rather than referenced: an example has no identity
     * of its own and is never read except as part of its problem.
     */
    public record WorkedExample(String input, String output, String explanation) {
    }
}
