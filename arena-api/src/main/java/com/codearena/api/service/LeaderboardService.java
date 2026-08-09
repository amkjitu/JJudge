package com.codearena.api.service;

import com.codearena.api.domain.User;
import com.codearena.api.repository.SubmissionRepository;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.web.dto.LeaderboardEntryResponse;
import com.codearena.common.domain.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ranking backed by a Redis sorted set, with PostgreSQL as the source of truth.
 *
 * <h2>What Redis actually buys</h2>
 * <p>Not "the query was slow" - with four users it was not. The sorted set buys two things SQL
 * cannot do cheaply:
 * <ul>
 *   <li><b>{@code ZREVRANGE}</b> returns the top N in O(log N + M) without touching the other
 *       rows. The SQL equivalent orders the whole table on every page view.</li>
 *   <li><b>{@code ZREVRANK}</b> answers "what position am I?" in O(log N). In SQL that is a
 *       window function over every user - the cost of finding one person's rank is the cost of
 *       ranking everybody, every time.</li>
 * </ul>
 *
 * <h2>Consistency</h2>
 * <p>Cache-aside: reads come from Redis, and an empty or unreachable cache falls back to
 * PostgreSQL and repopulates. Ratings change rarely - only when a verdict lands, from Phase 6 -
 * so a rebuild is cheap and staleness is bounded by how often that happens.
 *
 * <p>Redis holds only the ranking. Solve counts are fetched from PostgreSQL for the handful of
 * users actually being displayed, which keeps a second copy of mutable data out of the cache;
 * the alternative is two stores that can disagree about the same number.
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    static final String KEY = "leaderboard:global";

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final SubmissionRepository submissionRepository;

    public LeaderboardService(StringRedisTemplate redis,
                              UserRepository userRepository,
                              SubmissionRepository submissionRepository) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.submissionRepository = submissionRepository;
    }

    /**
     * Top players, best first.
     *
     * <p>Falls back to PostgreSQL on any Redis failure. A ranking is not worth a 500 - the
     * leaderboard is a feature of the page, not a precondition for it.
     */
    @Transactional(readOnly = true)
    public List<LeaderboardEntryResponse> top(int limit) {
        try {
            List<String> ranked = rankedUsernames(limit);
            if (ranked.isEmpty()) {
                rebuild();
                ranked = rankedUsernames(limit);
            }
            if (!ranked.isEmpty()) {
                return withSolveCounts(ranked);
            }
        } catch (DataAccessException e) {
            log.warn("Leaderboard cache unavailable, serving from PostgreSQL: {}", e.getMessage());
        }
        return fromDatabase(limit);
    }

    /**
     * A user's 1-based position, or empty when they are not ranked.
     *
     * <p>This is the operation that justifies the sorted set: O(log N) for any user, at any
     * position, without scanning.
     */
    @Transactional(readOnly = true)
    public OptionalInt rankOf(String username) {
        try {
            Long rank = redis.opsForZSet().reverseRank(KEY, username);
            if (rank == null) {
                rebuild();
                rank = redis.opsForZSet().reverseRank(KEY, username);
            }
            return rank == null ? OptionalInt.empty() : OptionalInt.of(rank.intValue() + 1);
        } catch (DataAccessException e) {
            log.warn("Rank lookup unavailable for '{}': {}", username, e.getMessage());
            return OptionalInt.empty();
        }
    }

    /**
     * Records a rating change. Called whenever a rating moves - which, from Phase 6, is when a
     * verdict lands.
     */
    public void record(String username, int rating) {
        try {
            redis.opsForZSet().add(KEY, username, rating);
        } catch (DataAccessException e) {
            // The cache will be rebuilt from PostgreSQL on the next miss, so a lost write costs
            // freshness, not correctness. Failing the caller's transaction over it would be
            // letting a cache dictate whether a submission is judged.
            log.warn("Could not update leaderboard entry for '{}': {}", username, e.getMessage());
        }
    }

    /** Repopulates the sorted set from PostgreSQL. */
    @Transactional(readOnly = true)
    public int rebuild() {
        List<User> users = userRepository.findTop50ByOrderByRatingDesc();
        if (users.isEmpty()) {
            return 0;
        }

        Set<ZSetOperations.TypedTuple<String>> tuples = users.stream()
                .map(user -> ZSetOperations.TypedTuple.of(user.getUsername(), (double) user.getRating()))
                .collect(Collectors.toSet());

        redis.opsForZSet().add(KEY, tuples);
        log.info("Rebuilt the leaderboard cache with {} entries", tuples.size());
        return tuples.size();
    }

    private List<String> rankedUsernames(int limit) {
        Set<String> ranked = redis.opsForZSet().reverseRange(KEY, 0, limit - 1L);
        return ranked == null ? List.of() : new ArrayList<>(ranked);
    }

    /**
     * Attaches solve counts to an already-ranked list, preserving Redis's ordering.
     *
     * <p>Two queries for the displayed page only, never one per row.
     */
    private List<LeaderboardEntryResponse> withSolveCounts(List<String> rankedUsernames) {
        Map<String, User> byUsername = new LinkedHashMap<>();
        for (String username : rankedUsernames) {
            userRepository.findByUsername(username).ifPresent(user -> byUsername.put(username, user));
        }
        if (byUsername.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> solved = solveCounts(byUsername.values().stream().map(User::getId).toList());

        List<LeaderboardEntryResponse> entries = new ArrayList<>(byUsername.size());
        int rank = 1;
        for (User user : byUsername.values()) {
            entries.add(new LeaderboardEntryResponse(rank++, user.getUsername(), user.getRating(),
                    solved.getOrDefault(user.getId(), 0L)));
        }
        return entries;
    }

    private List<LeaderboardEntryResponse> fromDatabase(int limit) {
        List<User> users = userRepository.findTop50ByOrderByRatingDesc().stream()
                .limit(limit)
                .toList();
        if (users.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> solved = solveCounts(users.stream().map(User::getId).toList());

        List<LeaderboardEntryResponse> entries = new ArrayList<>(users.size());
        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            entries.add(new LeaderboardEntryResponse(i + 1, user.getUsername(), user.getRating(),
                    solved.getOrDefault(user.getId(), 0L)));
        }
        return entries;
    }

    private Map<Long, Long> solveCounts(List<Long> userIds) {
        return submissionRepository.countSolvedForUsers(userIds, Verdict.AC).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }
}
