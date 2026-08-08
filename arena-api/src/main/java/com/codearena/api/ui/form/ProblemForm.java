package com.codearena.api.ui.form;

import com.codearena.api.web.dto.CreateProblemRequest;
import com.codearena.api.web.dto.ProblemDetailResponse;
import com.codearena.api.web.dto.UpdateProblemRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Form-backing bean for admin problem authoring, shared by the create and edit pages.
 *
 * <p>Tags arrive from a multi-select as a set of names. There is deliberately no difficulty
 * field: it is derived from the rating, and offering it as an input would invite the two to
 * disagree.
 */
public class ProblemForm {

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 200)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
            message = "must be lower-case words separated by single hyphens")
    private String slug;

    @NotNull
    @Min(0)
    @Max(4000)
    private Integer rating;

    @Min(100)
    @Max(20000)
    private Integer timeLimitMs = 1000;

    @Min(16)
    @Max(1024)
    private Integer memoryLimitMb = 256;

    @NotNull
    @Size(min = 1, message = "pick at least one tag")
    private Set<String> tags = new LinkedHashSet<>();

    public static ProblemForm from(ProblemDetailResponse problem) {
        ProblemForm form = new ProblemForm();
        form.title = problem.title();
        form.slug = problem.slug();
        form.rating = problem.rating();
        form.timeLimitMs = problem.timeLimitMs();
        form.memoryLimitMb = problem.memoryLimitMb();
        form.tags = new LinkedHashSet<>(problem.tags());
        return form;
    }

    public CreateProblemRequest toCreateRequest() {
        return new CreateProblemRequest(title, slug, rating, timeLimitMs, memoryLimitMb, tags);
    }

    public UpdateProblemRequest toUpdateRequest() {
        return new UpdateProblemRequest(title, rating, timeLimitMs, memoryLimitMb, tags);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public Integer getTimeLimitMs() {
        return timeLimitMs;
    }

    public void setTimeLimitMs(Integer timeLimitMs) {
        this.timeLimitMs = timeLimitMs;
    }

    public Integer getMemoryLimitMb() {
        return memoryLimitMb;
    }

    public void setMemoryLimitMb(Integer memoryLimitMb) {
        this.memoryLimitMb = memoryLimitMb;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags;
    }
}
