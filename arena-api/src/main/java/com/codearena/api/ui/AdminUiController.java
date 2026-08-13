package com.codearena.api.ui;

import com.codearena.api.service.ProblemAuthoringService;
import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.TagService;
import com.codearena.api.ui.form.ProblemForm;
import com.codearena.api.ui.form.StatementForm;
import com.codearena.api.ui.form.TestCaseForm;
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
    private final ProblemAuthoringService authoringService;

    public AdminUiController(ProblemService problemService, TagService tagService,
                             ProblemAuthoringService authoringService) {
        this.problemService = problemService;
        this.tagService = tagService;
        this.authoringService = authoringService;
    }

    @ModelAttribute("allTags")
    List<String> allTags() {
        return tagService.findAllWithPrerequisites().stream().map(TagResponse::name).toList();
    }

    @GetMapping
    public String list(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                       Pageable pageable, Model model) {
        var page = problemService.search(ProblemFilter.none(), pageable);
        model.addAttribute("page", page);
        // One MongoDB round trip for the whole page rather than one per row, so the status
        // column costs the same whether it shows 20 problems or 1.
        model.addAttribute("status", authoringService.statusOf(
                page.getContent().stream().map(p -> p.slug()).toList()));
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
        // The relational delete cascades within PostgreSQL, but MongoDB knows nothing about that
        // foreign key. Without this the statement and test cases outlive the problem, and a new
        // problem later given the same slug silently inherits both.
        authoringService.deleteAuthoring(slug);
        redirectAttributes.addFlashAttribute("flash", "Deleted '" + slug + "'.");
        return "redirect:/admin/problems";
    }

    // --------------------------------------------------------------------------------------
    // Statement and test cases.
    //
    // Separate pages rather than more fields on the problem form. They are edited at different
    // times by different people - prose when the problem is written, cases when it is made
    // judgeable - and a single form long enough to hold both would make every save a save of
    // everything, so two editors working on one problem would overwrite each other's half.
    // --------------------------------------------------------------------------------------

    @GetMapping("/{slug}/statement")
    public String statementForm(@PathVariable String slug, Model model) {
        StatementForm form = authoringService.findStatement(slug)
                .map(StatementForm::from)
                .orElseGet(StatementForm::new);

        // One empty row so the page has something to type into, and something for the "add"
        // button to clone. An empty list renders no rows at all, which looks like a broken page.
        if (form.getExamples().isEmpty()) {
            form.getExamples().add(new StatementForm.ExampleForm());
        }

        model.addAttribute("problem", problemService.getDetail(slug));
        model.addAttribute("form", form);
        model.addAttribute("status", authoringService.statusOf(slug));
        return "admin/problem-statement";
    }

    @PostMapping("/{slug}/statement")
    public String saveStatement(@PathVariable String slug,
                                @Valid @ModelAttribute("form") StatementForm form,
                                BindingResult binding,
                                Model model,
                                RedirectAttributes redirectAttributes) {

        // An example needs both halves. Checked here rather than with @NotBlank on the fields
        // because a wholly blank row is a spare the editor did not use and is simply dropped -
        // only a half-filled one is a mistake, and that takes both fields to recognise.
        for (int i = 0; i < form.getExamples().size(); i++) {
            StatementForm.ExampleForm example = form.getExamples().get(i);
            if (example.isBlank()) {
                continue;
            }
            if (example.getInput().isBlank()) {
                binding.rejectValue("examples[" + i + "].input", "required",
                        "an example needs an input");
            }
            if (example.getOutput().isBlank()) {
                binding.rejectValue("examples[" + i + "].output", "required",
                        "an example needs the output it produces");
            }
        }

        if (binding.hasErrors()) {
            model.addAttribute("problem", problemService.getDetail(slug));
            model.addAttribute("status", authoringService.statusOf(slug));
            return "admin/problem-statement";
        }

        authoringService.saveStatement(slug, form.getStatementMarkdown(),
                form.getEditorialMarkdown(), form.toExamples());

        redirectAttributes.addFlashAttribute("flash", "Saved the statement for '" + slug + "'.");
        return "redirect:/admin/problems/" + slug + "/statement";
    }

    @GetMapping("/{slug}/test-cases")
    public String testCaseForm(@PathVariable String slug, Model model) {
        TestCaseForm form = authoringService.findTestCases(slug)
                .map(TestCaseForm::from)
                .orElseGet(TestCaseForm::new);

        if (form.getCases().isEmpty()) {
            form.getCases().add(new TestCaseForm.CaseForm());
        }

        model.addAttribute("problem", problemService.getDetail(slug));
        model.addAttribute("form", form);
        model.addAttribute("status", authoringService.statusOf(slug));
        return "admin/problem-test-cases";
    }

    @PostMapping("/{slug}/test-cases")
    public String saveTestCases(@PathVariable String slug,
                                @Valid @ModelAttribute("form") TestCaseForm form,
                                BindingResult binding,
                                Model model,
                                RedirectAttributes redirectAttributes) {

        List<TestCaseForm.CaseForm> filled = form.filled();

        // Saving nothing is allowed and means "this problem has no cases", which the judge
        // reports honestly by falling back to a simulated verdict. It is worth saying out loud
        // on the way past, because it is rarely what somebody on this page intended.
        if (!filled.isEmpty() && filled.stream().noneMatch(TestCaseForm.CaseForm::isSample)) {
            binding.reject("noSample",
                    "Mark at least one case as a sample. Samples are what the problem page shows, "
                            + "and a problem with none gives a reader nothing to check against.");
        }
        for (int i = 0; i < form.getCases().size(); i++) {
            TestCaseForm.CaseForm testCase = form.getCases().get(i);
            // An empty expected output is legitimate - a program can correctly print nothing -
            // so only a missing input is rejected.
            if (!testCase.isBlank() && testCase.getInput().isBlank()) {
                binding.rejectValue("cases[" + i + "].input", "required",
                        "a case needs an input; leave the whole row blank to discard it");
            }
        }

        if (binding.hasErrors()) {
            model.addAttribute("problem", problemService.getDetail(slug));
            model.addAttribute("status", authoringService.statusOf(slug));
            return "admin/problem-test-cases";
        }

        authoringService.saveTestCases(slug, form.toCases());

        redirectAttributes.addFlashAttribute("flash", filled.isEmpty()
                ? "Removed every test case from '" + slug + "'. It will be judged by simulation "
                        + "until cases are added."
                : "Saved " + filled.size() + " test case(s) for '" + slug + "'.");
        return "redirect:/admin/problems/" + slug + "/test-cases";
    }
}
