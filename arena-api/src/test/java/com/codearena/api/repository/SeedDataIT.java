package com.codearena.api.repository;

import com.codearena.api.domain.User;
import com.codearena.common.domain.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the seed migrations. If a seed file is edited into an inconsistent state - a problem
 * with no tags, a user_tag_stats row that claims more solves than attempts, a difficulty label
 * that disagrees with the numeric rating - these fail rather than silently poisoning the
 * recommendation engine's inputs.
 */
@DisplayName("Flyway migrations and seed data")
class SeedDataIT extends AbstractRepositoryIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProblemRepository problemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TagRepository tagRepository;

    @Test
    @DisplayName("all migrations applied successfully")
    void allMigrationsApplied() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        assertThat(rows).extracting(row -> row.get("version"))
                .contains("1", "2", "3", "4", "5");
    }

    @Test
    @DisplayName("seeds 40 problems, 30 tags and 4 users")
    void seedCounts() {
        assertThat(problemRepository.count()).isEqualTo(40);
        assertThat(tagRepository.count()).isEqualTo(30);
        assertThat(userRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("every problem carries at least one tag")
    void everyProblemIsTagged() {
        Integer untagged = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM problems p
                WHERE NOT EXISTS (SELECT 1 FROM problem_tags pt WHERE pt.problem_id = p.id)
                """, Integer.class);

        assertThat(untagged).isZero();
    }

    @Test
    @DisplayName("difficulty label agrees with the numeric rating")
    void difficultyMatchesRating() {
        Integer mismatched = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM problems
                WHERE (rating < 1200 AND difficulty <> 'EASY')
                   OR (rating BETWEEN 1200 AND 1699 AND difficulty <> 'MEDIUM')
                   OR (rating >= 1700 AND difficulty <> 'HARD')
                """, Integer.class);

        assertThat(mismatched).isZero();
    }

    @Test
    @DisplayName("every difficulty bucket is populated")
    void everyDifficultyIsRepresented() {
        for (Difficulty difficulty : Difficulty.values()) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM problems WHERE difficulty = ?", Integer.class, difficulty.name());
            assertThat(count).as("problems with difficulty %s", difficulty).isPositive();
        }
    }

    @Test
    @DisplayName("every tag is used by at least one problem")
    void everyTagIsUsed() {
        List<String> unusedTags = jdbcTemplate.queryForList("""
                SELECT t.name FROM tags t
                WHERE NOT EXISTS (SELECT 1 FROM problem_tags pt WHERE pt.tag_id = t.id)
                """, String.class);

        assertThat(unusedTags).isEmpty();
    }

    @Test
    @DisplayName("derived user_tag_stats never claim more solves than attempts")
    void tagStatsAreInternallyConsistent() {
        Integer broken = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_tag_stats WHERE solved_count > attempt_count OR solved_count < 0",
                Integer.class);

        assertThat(broken).isZero();
    }

    @Test
    @DisplayName("user_tag_stats agree with the submissions they were derived from")
    void tagStatsMatchSubmissions() {
        // Recompute the counters from scratch and diff against the stored rows.
        Integer drift = jdbcTemplate.queryForObject("""
                WITH expected AS (SELECT attempted.user_id,
                                         attempted.tag_id,
                                         COUNT(*) FILTER (WHERE attempted.solved) AS solved_count,
                                         COUNT(*)                                 AS attempt_count
                                  FROM (SELECT s.user_id,
                                               pt.tag_id,
                                               bool_or(s.verdict = 'AC') AS solved
                                        FROM submissions s
                                                 JOIN problem_tags pt ON pt.problem_id = s.problem_id
                                        GROUP BY s.user_id, pt.tag_id, s.problem_id) AS attempted
                                  GROUP BY attempted.user_id, attempted.tag_id)
                SELECT COUNT(*)
                FROM expected e
                         FULL OUTER JOIN user_tag_stats a
                                         ON a.user_id = e.user_id AND a.tag_id = e.tag_id
                WHERE a.user_id IS NULL
                   OR e.user_id IS NULL
                   OR a.solved_count <> e.solved_count
                   OR a.attempt_count <> e.attempt_count
                """, Integer.class);

        assertThat(drift).isZero();
    }

    @Test
    @DisplayName("demo users have the skill profile the fixtures promise")
    void demoUsersHaveDistinctProfiles() {
        User alice = userRepository.findByUsername("alice").orElseThrow();
        User bob = userRepository.findByUsername("bob").orElseThrow();
        User carol = userRepository.findByUsername("carol").orElseThrow();

        assertThat(alice.getRating()).isLessThan(bob.getRating());
        assertThat(bob.getRating()).isLessThan(carol.getRating());
        assertThat(alice.getPasswordHash()).startsWith("$2a$");
    }
}
