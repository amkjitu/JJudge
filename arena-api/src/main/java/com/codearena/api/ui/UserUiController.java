package com.codearena.api.ui;

import com.codearena.api.service.UserService;
import com.codearena.api.web.dto.ProgressPointResponse;
import com.codearena.api.web.dto.UserProfileResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Controller
public class UserUiController {

    /** Chart legibility, not a data limit: a 30-bar chart is unreadable on a phone. */
    private static final int CHART_TAG_LIMIT = 10;

    private final UserService userService;
    private final ObjectMapper objectMapper;

    public UserUiController(UserService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/me")
    public String me(Authentication authentication) {
        return "redirect:/users/" + authentication.getName();
    }

    @GetMapping("/users/{username}")
    public String profile(@PathVariable String username, Model model) throws JsonProcessingException {
        UserProfileResponse profile = userService.getProfile(username);
        List<ProgressPointResponse> progress = userService.progress(username);

        model.addAttribute("profile", profile);
        model.addAttribute("progress", progress);

        // Chart data is serialised to JSON here rather than assembled in the template. Building
        // JavaScript literals out of Thymeleaf loops is how XSS gets into a page: a username or
        // tag containing a quote would break out of the string. th:inline="javascript" escapes
        // what it interpolates, and handing it a single pre-serialised string keeps the escaping
        // in one place.
        List<Map<String, Object>> byTag = profile.tagStats().stream()
                .filter(stat -> stat.solvedCount() > 0)
                .sorted(Comparator.comparingInt(stat -> -stat.solvedCount()))
                .limit(CHART_TAG_LIMIT)
                .map(stat -> Map.<String, Object>of("tag", stat.tag(), "solved", stat.solvedCount()))
                .toList();

        model.addAttribute("solvedByTagJson", objectMapper.writeValueAsString(byTag));
        model.addAttribute("progressJson", objectMapper.writeValueAsString(progress));

        return "users/profile";
    }
}
