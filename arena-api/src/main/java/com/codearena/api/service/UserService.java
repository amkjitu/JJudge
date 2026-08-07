package com.codearena.api.service;

import com.codearena.api.domain.User;
import com.codearena.api.domain.UserTagStats;
import com.codearena.api.repository.SubmissionRepository;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.repository.UserTagStatsRepository;
import com.codearena.api.web.dto.UserProfileResponse;
import com.codearena.api.web.dto.UserTagStatResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;


@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final SubmissionRepository submissionRepository;
    private final UserTagStatsRepository userTagStatsRepository;

    public UserService(UserRepository userRepository,
                       SubmissionRepository submissionRepository,
                       UserTagStatsRepository userTagStatsRepository) {
        this.userRepository = userRepository;
        this.submissionRepository = submissionRepository;
        this.userTagStatsRepository = userTagStatsRepository;
    }

    public User getByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));
    }

    /**
     * Assembled here rather than in a MapStruct mapper: the counts come from three different
     * repositories and the tag list is sorted by a derived value, none of which is a
     * field-to-field mapping.
     *
     * <p>Tags are returned weakest-first, which is the order the profile page and the
     * recommendation panel both want.
     */
    public UserProfileResponse getProfile(String username) {
        User user = getByUsername(username);

        long solvedCount = submissionRepository.findSolvedProblemIds(user.getId()).size();
        long submissionCount = submissionRepository.countByUserId(user.getId());

        List<UserTagStatResponse> tagStats = userTagStatsRepository.findByUserId(user.getId()).stream()
                .map(UserService::toTagStat)
                .sorted(Comparator.comparingDouble(UserTagStatResponse::proficiency)
                        .thenComparing(UserTagStatResponse::tag))
                .toList();

        return new UserProfileResponse(
                user.getId(),
                user.getUsername(),
                user.getRole(),
                user.getRating(),
                user.getCreatedAt(),
                solvedCount,
                submissionCount,
                tagStats
        );
    }

    private static UserTagStatResponse toTagStat(UserTagStats stats) {
        return new UserTagStatResponse(
                stats.getTag().getName(),
                stats.getSolvedCount(),
                stats.getAttemptCount(),
                stats.proficiency(ProficiencyScoring.DEFAULT_SMOOTHING)
        );
    }
}
