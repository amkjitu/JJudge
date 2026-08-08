package com.codearena.api.ui;

import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.SubmissionService;
import com.codearena.api.service.TagService;
import com.codearena.api.ui.form.SubmissionForm;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.common.domain.Difficulty;
import com.codearena.common.domain.Language;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
public class ProblemUiController {

    private final ProblemService problemService;
    private final SubmissionService submissionService;
    private final TagService tagService;

    public ProblemUiController(ProblemService problemService,
                               SubmissionService submissionService,
                               TagService tagService) {
        this.problemService = problemService;
        this.submissionService = submissionService;
        this.tagService = tagService;
    }

    @ModelAttribute("allTags")
    List<String> allTags() {
        return tagService.findAllWithPrerequisites().stream().map(t -> t.name()).toList();
    }

    @ModelAttribute("allDifficulties")
    Difficulty[] allDifficulties() {
        return Difficulty.values();
    }

    @GetMapping("/problems")
    public String list(@RequestParam(required = false) String tag,
                       @RequestParam(required = false) Difficulty difficulty,
                       @RequestParam(required = false) Integer minRating,
                       @RequestParam(required = false) Integer maxRating,
                       @RequestParam(required = false) String search,
                       @PageableDefault(size = 20, sort = "rating", direction = Sort.Direction.ASC)
                       Pageable pageable,
                       Authentication authentication,
                       Model model) {

        ProblemFilter filter = new ProblemFilter(tag, difficulty, minRating, maxRating, search);
        model.addAttribute("page", problemService.search(filter, pageable));
        model.addAttribute("filter", filter);

        // Ticks the "solved" column without an N+1: one query returns every problem id this
        // user has cleared, and the template does set membership.
        if (UiSecurity.isAuthenticated(authentication)) {
            model.addAttribute("solvedIds", submissionService.solvedProblemIds(authentication.getName()));
        }

        return "problems/list";
    }

    @GetMapping("/problems/{slug}")
    public String detail(@PathVariable String slug,
                         Authentication authentication,
                         Model model) {

        model.addAttribute("problem", problemService.getDetail(slug));
        model.addAttribute("languages", Language.values());

        if (!model.containsAttribute("submissionForm")) {
            model.addAttribute("submissionForm", new SubmissionForm());
        }

        if (UiSecurity.isAuthenticated(authentication)) {
            model.addAttribute("mySubmissions",
                    submissionService.findByUsernameAndProblem(authentication.getName(), slug));
        }

        return "problems/detail";
    }

    @PostMapping("/problems/{slug}/submit")
    public String submit(@PathVariable String slug,
                         @Valid @ModelAttribute("submissionForm") SubmissionForm form,
                         BindingResult binding,
                         Authentication authentication,
                         Model model,
                         RedirectAttributes redirectAttributes) {

        if (binding.hasErrors()) {
            // Re-render in place rather than redirecting: a redirect would discard the code the
            // user just typed, which is the one thing they cannot easily retype.
            return detail(slug, authentication, model);
        }

        SubmissionResponse submission =
                submissionService.create(authentication.getName(), form.toRequest(slug));

        redirectAttributes.addFlashAttribute("flash",
                "Submission #" + submission.id() + " queued.");
        return "redirect:/submissions/" + submission.id();
    }
}
