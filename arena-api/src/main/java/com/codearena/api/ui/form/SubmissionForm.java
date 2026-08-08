package com.codearena.api.ui.form;

import com.codearena.api.web.dto.CreateSubmissionRequest;
import com.codearena.common.domain.Language;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Form-backing bean for the code editor on the problem detail page. The slug comes from the
 * path, not the form, so it cannot be pointed at a different problem than the one on screen.
 */
public class SubmissionForm {

    @NotNull
    private Language language = Language.JAVA;

    @NotBlank(message = "write some code before submitting")
    @Size(max = 65536, message = "source code must not exceed 64 KiB")
    private String sourceCode = "";

    public CreateSubmissionRequest toRequest(String problemSlug) {
        return new CreateSubmissionRequest(problemSlug, language, sourceCode);
    }

    public Language getLanguage() {
        return language;
    }

    public void setLanguage(Language language) {
        this.language = language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }
}
