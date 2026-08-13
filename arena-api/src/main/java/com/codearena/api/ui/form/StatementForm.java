package com.codearena.api.ui.form;

import com.codearena.api.mongo.ProblemStatement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.AutoPopulatingList;

import java.util.List;

/**
 * The prose half of a problem: what a reader is shown.
 *
 * <p>Backed by {@link AutoPopulatingList} so the browser can add example rows. Spring binds
 * {@code examples[3].input} by asking the list for index 3, and a plain {@code ArrayList} throws
 * {@code IndexOutOfBoundsException} for anything past its current size — which presents as a
 * 500 the moment somebody clicks "add", and only then.
 */
public class StatementForm {

    @NotBlank(message = "a problem needs a statement")
    @Size(max = 20000, message = "must not exceed 20000 characters")
    private String statementMarkdown = "";

    /** Optional: a problem may ship without an editorial, and often should until it is written. */
    @Size(max = 20000, message = "must not exceed 20000 characters")
    private String editorialMarkdown = "";

    @Valid
    private List<ExampleForm> examples = new AutoPopulatingList<>(index -> new ExampleForm());

    public static StatementForm from(ProblemStatement statement) {
        StatementForm form = new StatementForm();
        form.statementMarkdown = statement.getStatementMarkdown();
        form.editorialMarkdown = statement.getEditorialMarkdown() == null
                ? "" : statement.getEditorialMarkdown();
        form.examples = new AutoPopulatingList<>(index -> new ExampleForm());
        statement.getExamples().forEach(e ->
                form.examples.add(new ExampleForm(e.input(), e.output(), e.explanation())));
        return form;
    }

    /** Rows the editor left entirely blank are dropped rather than saved as empty examples. */
    public List<ProblemStatement.WorkedExample> toExamples() {
        return examples.stream()
                .filter(e -> !e.isBlank())
                .map(e -> new ProblemStatement.WorkedExample(
                        e.getInput(), e.getOutput(),
                        e.getExplanation() == null || e.getExplanation().isBlank()
                                ? null : e.getExplanation()))
                .toList();
    }

    public String getStatementMarkdown() {
        return statementMarkdown;
    }

    public void setStatementMarkdown(String statementMarkdown) {
        this.statementMarkdown = statementMarkdown;
    }

    public String getEditorialMarkdown() {
        return editorialMarkdown;
    }

    public void setEditorialMarkdown(String editorialMarkdown) {
        this.editorialMarkdown = editorialMarkdown;
    }

    public List<ExampleForm> getExamples() {
        return examples;
    }

    public void setExamples(List<ExampleForm> examples) {
        this.examples = examples;
    }

    /**
     * One worked example. The explanation is optional; the pair is not, because an example that
     * shows input and no output teaches nothing.
     */
    public static class ExampleForm {

        private String input = "";
        private String output = "";
        private String explanation = "";

        public ExampleForm() {
        }

        public ExampleForm(String input, String output, String explanation) {
            this.input = input == null ? "" : input;
            this.output = output == null ? "" : output;
            this.explanation = explanation == null ? "" : explanation;
        }

        public boolean isBlank() {
            return input.isBlank() && output.isBlank() && explanation.isBlank();
        }

        public String getInput() {
            return input;
        }

        public void setInput(String input) {
            this.input = input;
        }

        public String getOutput() {
            return output;
        }

        public void setOutput(String output) {
            this.output = output;
        }

        public String getExplanation() {
            return explanation;
        }

        public void setExplanation(String explanation) {
            this.explanation = explanation;
        }
    }
}
