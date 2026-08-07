package com.codearena.api.repository;

import com.codearena.api.domain.Tag;
import com.codearena.api.domain.User;
import com.codearena.api.domain.UserTagStats;
import com.codearena.common.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UserRepository and UserTagStatsRepository")
class UserRepositoryIT extends AbstractRepositoryIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserTagStatsRepository userTagStatsRepository;

    @Test
    @DisplayName("finds by username and email")
    void lookups() {
        assertThat(userRepository.findByUsername("carol")).isPresent();
        assertThat(userRepository.findByEmail("admin@codearena.dev"))
                .get()
                .extracting(User::getRole)
                .isEqualTo(Role.ADMIN);
        assertThat(userRepository.existsByUsername("alice")).isTrue();
        assertThat(userRepository.existsByEmail("nobody@codearena.dev")).isFalse();
    }

    @Test
    @DisplayName("username uniqueness is enforced by the database, not just the application")
    void duplicateUsernameIsRejected() {
        User clash = User.builder()
                .username("alice")
                .email("someone-else@codearena.dev")
                .passwordHash("$2a$10$0000000000000000000000000000000000000000000000000000000000")
                .role(Role.USER)
                .rating(1200)
                .build();

        assertThatThrownBy(() -> userRepository.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("fetch-joins tag statistics in a single query")
    void loadsTagStatsEagerly() {
        User bob = userRepository.findByUsername("bob").orElseThrow();

        User loaded = userRepository.findByIdWithTagStats(bob.getId()).orElseThrow();

        assertThat(loaded.getTagStats()).isNotEmpty();
        assertThat(loaded.getTagStats()).allSatisfy(stat ->
                assertThat(stat.getTag().getName()).isNotBlank());
    }

    @Test
    @DisplayName("bob's weakest tags are dp and shortest-path, as the fixtures intend")
    void tagProficiencyReflectsTheSeededProfile() {
        Long bobId = userRepository.findByUsername("bob").orElseThrow().getId();

        Map<String, UserTagStats> byTag = userTagStatsRepository.findByUserId(bobId).stream()
                .collect(Collectors.toMap(s -> s.getTag().getName(), Function.identity()));

        assertThat(byTag).containsKeys("dp", "shortest-path", "arrays");

        // dp is his weak spot: he has cleared Kadane's (which is tagged dp) but failed every
        // other dp problem he has tried. A near-miss like this is a better fixture than a flat
        // zero - it proves the smoothed ratio, not just the presence of a solve, is what ranks
        // the tag as weak.
        UserTagStats dp = byTag.get("dp");
        assertThat(dp.getAttemptCount()).isGreaterThanOrEqualTo(4);
        assertThat(dp.getSolvedCount()).isLessThan(dp.getAttemptCount());
        assertThat(dp.proficiency(3.0)).isLessThan(0.25);

        // shortest-path he has never cracked at all
        assertThat(byTag.get("shortest-path").getSolvedCount()).isZero();
        assertThat(byTag.get("shortest-path").getAttemptCount()).isPositive();

        // arrays, by contrast, is comfortable territory
        UserTagStats arrays = byTag.get("arrays");
        assertThat(arrays.getSolvedCount()).isPositive();
        assertThat(arrays.proficiency(3.0)).isGreaterThan(dp.proficiency(3.0) * 2);
    }

    @Test
    @DisplayName("leaderboard-style top-N by rating is ordered descending")
    void topByRating() {
        List<User> top = userRepository.findTop50ByOrderByRatingDesc();

        assertThat(top).hasSize(4);
        assertThat(top).extracting(User::getRating).isSortedAccordingTo((a, b) -> b.compareTo(a));
        assertThat(top.get(0).getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("tag stats are keyed by the (user, tag) composite id")
    void compositeKeyRoundTrips() {
        Long aliceId = userRepository.findByUsername("alice").orElseThrow().getId();
        List<UserTagStats> stats = userTagStatsRepository.findByUserId(aliceId);

        assertThat(stats).isNotEmpty();
        UserTagStats first = stats.get(0);
        Tag tag = first.getTag();

        assertThat(first.getId().getUserId()).isEqualTo(aliceId);
        assertThat(first.getId().getTagId()).isEqualTo(tag.getId());
        assertThat(userTagStatsRepository.findById(first.getId())).isPresent();
        assertThat(userTagStatsRepository.countByUserId(aliceId)).isEqualTo(stats.size());
    }
}
