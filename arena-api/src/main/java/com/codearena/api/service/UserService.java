package com.codearena.api.service;

import com.codearena.api.domain.User;
import com.codearena.api.domain.UserTagStats;
import com.codearena.api.repository.SubmissionRepository;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.repository.UserTagStatsRepository;
import com.codearena.api.web.dto.LeaderboardEntryResponse;
import com.codearena.api.web.dto.ProgressPointResponse;
import com.codearena.api.web.dto.UserProfileResponse;
import com.codearena.api.web.dto.UserTagStatResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.common.domain.Verdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;


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

    /**
     * Top players by rating, with their solve counts.
     *
     * <p>Two queries rather than one join: fetch the ranked page of users, then count solves
     * for exactly those ids. Phase 5 replaces this with a Redis sorted set, at which point the
     * ranking stops touching PostgreSQL at all - this is the correct-but-unoptimised version
     * that the cached one will be measured against.
     */
    public List<LeaderboardEntryResponse> leaderboard() {
        List<User> ranked = userRepository.findTop50ByOrderByRatingDesc();
        if (ranked.isEmpty()) {
            return List.of();
        }

        List<Long> ids = ranked.stream().map(User::getId).toList();
        Map<Long, Long> solvedByUser = submissionRepository.countSolvedForUsers(ids, Verdict.AC).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));

        List<LeaderboardEntryResponse> entries = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            User user = ranked.get(i);
            entries.add(new LeaderboardEntryResponse(
                    i + 1,
                    user.getUsername(),
                    user.getRating(),
                    solvedByUser.getOrDefault(user.getId(), 0L)));
        }
        return entries;
    }

    /**
     * Cumulative distinct problems solved, bucketed by month.
     *
     * <p>Only the <em>first</em> accepted submission for a problem advances the curve - solving
     * the same problem again in a different language is not progress. Months with no activity
     * are filled in rather than skipped, so the chart's x-axis is evenly spaced instead of
     * compressing a six-month gap into one step.
     */
    public List<ProgressPointResponse> progress(String username) {
        User user = getByUsername(username);

        // Chronological, so the first row for a problem id is its first solve.
        Map<Long, YearMonth> firstSolve = new LinkedHashMap<>();
        for (Object[] row : submissionRepository.findAcceptedTimeline(user.getId(), Verdict.AC)) {
            Long problemId = (Long) row[0];
            Instant solvedAt = (Instant) row[1];
            firstSolve.putIfAbsent(problemId, YearMonth.from(solvedAt.atZone(ZoneOffset.UTC)));
        }

        if (firstSolve.isEmpty()) {
            return List.of();
        }

        Map<YearMonth, Long> solvesPerMonth = firstSolve.values().stream()
                .collect(Collectors.groupingBy(month -> month, TreeMap::new, Collectors.counting()));

        YearMonth cursor = solvesPerMonth.keySet().iterator().next();
        YearMonth last = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC));

        List<ProgressPointResponse> points = new ArrayList<>();
        long cumulative = 0;
        while (!cursor.isAfter(last)) {
            cumulative += solvesPerMonth.getOrDefault(cursor, 0L);
            points.add(new ProgressPointResponse(cursor.toString(), cumulative));
            cursor = cursor.plusMonths(1);
        }
        return points;
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
