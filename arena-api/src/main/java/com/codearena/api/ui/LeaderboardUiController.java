package com.codearena.api.ui;

import com.codearena.api.service.LeaderboardService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LeaderboardUiController {

    private final LeaderboardService leaderboardService;

    public LeaderboardUiController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @GetMapping("/leaderboard")
    public String leaderboard(Model model) {
        model.addAttribute("entries", leaderboardService.top(50));
        return "leaderboard";
    }
}
