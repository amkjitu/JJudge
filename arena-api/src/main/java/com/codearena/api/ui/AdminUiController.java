package com.codearena.api.ui;

import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.TagService;
import com.codearena.api.ui.form.ProblemForm;
import com.codearena.api.web.dto.TagResponse;
import com.codearena.api.web.error.DuplicateResourceException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Admin problem authoring.
 *
 * <p>{@code @PreAuthorize} on top of the {@code /admin/**} rule in the filter chain is
 * deliberate belt-and-braces: the path rule is the one that runs first and cheapest, and the
 * annotation is what still holds if someone later reorganises the URL structure.
 */
@Controller
@RequestMapping("/admin/problems")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUiController {

    private final ProblemService problemService;
    private final TagService tagService;

    public AdminUiController(ProblemService problemService, TagService tagService) {
        this.problemService = problemService;
        this.tagService = tagService;
    }

    @ModelAttribute("allTags")
    List<String> allTags() {
        return tagService.findAllWithPrerequisites().stream().map(TagResponse::name).toList();
    }

    @GetMapping
    public String list(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                       Pageable pageable, Model model) {
        model.addAttribute("page", problemService.search(ProblemFilter.none(), pageable));
        return "admin/problems";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new ProblemForm());
        model.addAttribute("editing", false);
        return "admin/problem-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") ProblemForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            model.addAttribute("editing", false);
            return "admin/problem-form";
        }

        try {
            problemService.create(form.toCreateRequest());
        } catch (DuplicateResourceException e) {
            binding.addError(new FieldError("form", "slug", form.getSlug(),
                    false, null, null, "is already in use"));
            model.addAttribute("editing", false);
            return "admin/problem-form";
        } catch (IllegalArgumentException e) {
            // Thrown when a tag name does not exist; surfaced on the tags field rather than as
            // an anonymous banner.
            binding.addError(new FieldError("form", "tags", form.getTags(),
                    false, null, null, e.getMessage()));
            model.addAttribute("editing", false);
            return "admin/problem-form";
        }

        redirectAttributes.addFlashAttribute("flash", "Created '" + form.getSlug() + "'.");
        return "redirect:/admin/problems";
    }

    @GetMapping("/{slug}/edit")
    public String editForm(@PathVariable String slug, Model model) {
        model.addAttribute("form", ProblemForm.from(problemService.getDetail(slug)));
        model.addAttribute("editing", true);
        return "admin/problem-form";
    }

    @PostMapping("/{slug}")
    public String update(@PathVariable String slug,
                         @Valid @ModelAttribute("form") ProblemForm form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            model.addAttribute("editing", true);
            return "admin/problem-form";
        }

        try {
            problemService.update(slug, form.toUpdateRequest());
        } catch (IllegalArgumentException e) {
            binding.addError(new FieldError("form", "tags", form.getTags(),
                    false, null, null, e.getMessage()));
            model.addAttribute("editing", true);
            return "admin/problem-form";
        }

        redirectAttributes.addFlashAttribute("flash", "Updated '" + slug + "'.");
        return "redirect:/admin/problems";
    }

    @PostMapping("/{slug}/delete")
    public String delete(@PathVariable String slug, RedirectAttributes redirectAttributes) {
        problemService.delete(slug);
        redirectAttributes.addFlashAttribute("flash", "Deleted '" + slug + "'.");
        return "redirect:/admin/problems";
    }
}
