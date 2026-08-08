package com.codearena.api.ui;

import com.codearena.api.service.ProblemFilter;
import com.codearena.api.service.ProblemService;
import com.codearena.api.service.UserService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The landing page: a signed-out visitor gets the pitch and the newest problems, a signed-in
 * one gets their own numbers.
 */
@Controller
public class HomeUiController {

    private final ProblemService problemService;
    private final UserService userService;

    public HomeUiController(ProblemService problemService, UserService userService) {
        this.problemService = problemService;
        this.userService = userService;
    }

    @GetMapping("/")
    public String home(Authentication authentication, Model model) {
        model.addAttribute("recentProblems", problemService.search(
                ProblemFilter.none(),
                PageRequest.of(0, 6, Sort.by("createdAt").descending())).getContent());

        model.addAttribute("leaderboard", userService.leaderboard().stream().limit(5).toList());

        if (UiSecurity.isAuthenticated(authentication)) {
            model.addAttribute("profile", userService.getProfile(authentication.getName()));
        }

        return "index";
    }
}
