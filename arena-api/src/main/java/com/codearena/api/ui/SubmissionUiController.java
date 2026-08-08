package com.codearena.api.ui;

import com.codearena.api.service.SubmissionService;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class SubmissionUiController {

    private final SubmissionService submissionService;

    public SubmissionUiController(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    @GetMapping("/submissions")
    public String mine(@PageableDefault(size = 20) Pageable pageable,
                       Authentication authentication,
                       Model model) {
        model.addAttribute("page", submissionService.findByUsername(authentication.getName(), pageable));
        return "submissions/list";
    }

    @GetMapping("/submissions/{id}")
    public String detail(@PathVariable Long id, Authentication authentication, Model model) {
        SubmissionResponse submission = submissionService.getById(id);

        // Source code is the author's alone. Anyone may see that a submission exists and what
        // verdict it got - that is on the problem page - but not the solution behind it.
        if (!submission.username().equals(authentication.getName())) {
            throw new AccessDeniedException("Submission " + id + " belongs to another user");
        }

        model.addAttribute("submission", submission);
        model.addAttribute("sourceCode", submissionService.getSourceCode(id)
                .orElseThrow(() -> new ResourceNotFoundException("Submission source", id)));

        return "submissions/detail";
    }
}
