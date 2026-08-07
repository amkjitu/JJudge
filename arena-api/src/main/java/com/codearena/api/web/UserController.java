package com.codearena.api.web;

import com.codearena.api.service.SubmissionService;
import com.codearena.api.service.UserService;
import com.codearena.api.web.dto.PageResponse;
import com.codearena.api.web.dto.SubmissionResponse;
import com.codearena.api.web.dto.UserProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Public profiles and per-topic statistics")
public class UserController {

    private final UserService userService;
    private final SubmissionService submissionService;

    public UserController(UserService userService, SubmissionService submissionService) {
        this.userService = userService;
        this.submissionService = submissionService;
    }

    @GetMapping("/{username}")
    @Operation(summary = "Public profile",
            description = "Includes per-topic proficiency, ordered weakest topic first.")
    public UserProfileResponse profile(@PathVariable String username) {
        return userService.getProfile(username);
    }

    @GetMapping("/{username}/submissions")
    @Operation(summary = "A user's submission history, newest first")
    public PageResponse<SubmissionResponse> submissions(@PathVariable String username,
                                                        @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.from(submissionService.findByUsername(username, pageable));
    }
}
