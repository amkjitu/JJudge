package com.codearena.api.ui;

import com.codearena.api.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LeaderboardUiController {

    private final UserService userService;

    public LeaderboardUiController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/leaderboard")
    public String leaderboard(Model model) {
        model.addAttribute("entries", userService.leaderboard());
        return "leaderboard";
    }
}
