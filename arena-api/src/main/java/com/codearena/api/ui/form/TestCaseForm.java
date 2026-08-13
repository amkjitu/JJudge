package com.codearena.api.ui.form;

import com.codearena.api.mongo.ProblemTestCases;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.util.AutoPopulatingList;

import java.util.ArrayList;
import java.util.List;

/**
 * The judged half of a problem: what a submission is actually run against.
 *
 * <p>Indices are assigned on save from the row order rather than typed in. They have to be
 * contiguous and 1-based for "failed on case 4" to mean anything, and asking an editor to
 * maintain that by hand while inserting a case in the middle is asking for a gap nobody notices
 * until a verdict cites a case that does not exist.
 *
 * @see StatementForm for why this uses {@link AutoPopulatingList}
 */
public class TestCaseForm {

    @Valid
    private List<CaseForm> cases = new AutoPopulatingList<>(index -> new CaseForm());

    public static TestCaseForm from(ProblemTestCases testCases) {
        TestCaseForm form = new TestCaseForm();
        form.cases = new AutoPopulatingList<>(index -> new CaseForm());
        testCases.getCases().forEach(c ->
                form.cases.add(new CaseForm(c.input(), c.expectedOutput(), c.sample())));
        return form;
    }

    /** Rows left entirely blank are dropped; an editor adding three rows and filling one meant one. */
    public List<CaseForm> filled() {
        return cases.stream().filter(c -> !c.isBlank()).toList();
    }

    public List<ProblemTestCases.Case> toCases() {
        List<ProblemTestCases.Case> out = new ArrayList<>();
        int index = 1;
        for (CaseForm c : filled()) {
            out.add(new ProblemTestCases.Case(index++, c.getInput(), c.getExpectedOutput(),
                    c.isSample()));
        }
        return out;
    }

    public List<CaseForm> getCases() {
        return cases;
    }

    public void setCases(List<CaseForm> cases) {
        this.cases = cases;
    }

    /**
     * One case.
     *
     * <p>Neither field is {@code @NotBlank}: a wholly blank row is a spare the editor did not
     * use and is discarded, and a case whose <em>expected output</em> is legitimately empty is a
     * real thing — a program that prints nothing for some inputs. Half-filled rows are caught in
     * the controller, where both fields can be looked at together.
     */
    public static class CaseForm {

        @Size(max = 100_000, message = "must not exceed 100000 characters")
        private String input = "";

        @Size(max = 100_000, message = "must not exceed 100000 characters")
        private String expectedOutput = "";

        /** Shown on the problem page as a worked example, as well as being judged. */
        private boolean sample;

        public CaseForm() {
        }

        public CaseForm(String input, String expectedOutput, boolean sample) {
            this.input = input == null ? "" : input;
            this.expectedOutput = expectedOutput == null ? "" : expectedOutput;
            this.sample = sample;
        }

        public boolean isBlank() {
            return input.isBlank() && expectedOutput.isBlank() && !sample;
        }

        public String getInput() {
            return input;
        }

        public void setInput(String input) {
            this.input = input;
        }

        public String getExpectedOutput() {
            return expectedOutput;
        }

        public void setExpectedOutput(String expectedOutput) {
            this.expectedOutput = expectedOutput;
        }

        public boolean isSample() {
            return sample;
        }

        public void setSample(boolean sample) {
            this.sample = sample;
        }
    }
}
