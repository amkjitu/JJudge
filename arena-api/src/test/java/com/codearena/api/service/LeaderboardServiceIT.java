package com.codearena.api.service;

import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
import com.codearena.api.web.dto.LeaderboardEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The leaderboard against a real Redis and a real PostgreSQL.
 *
 * <p>Sorted-set ordering, rank arithmetic and the cache-aside rebuild are all behaviours of
 * Redis itself; a mocked template would assert that the right methods were called and prove
 * nothing about whether the ranking is correct.
 */
@SpringBootTest
@DisplayName("LeaderboardService")
class LeaderboardServiceIT {

    @Autowired
    private LeaderboardService leaderboardService;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        // No broker in this context. Without disabling listener startup the @KafkaListener
        // containers spin retrying a connection that will never succeed - seconds of log noise
        // per test class, and a slower shutdown for no benefit. SubmissionPipelineIT is the one
        // that actually wants a broker.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        RedisTestContainer.registerProperties(registry);
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
    }

    @BeforeEach
    void clearCache() {
        redis.delete(LeaderboardService.KEY);
    }

    @Test
    @DisplayName("rebuilds itself from PostgreSQL on a cold cache")
    void cacheAsideRebuild() {
        assertThat(redis.hasKey(LeaderboardService.KEY)).isFalse();

        List<LeaderboardEntryResponse> top = leaderboardService.top(10);

        assertThat(top).isNotEmpty();
        assertThat(redis.hasKey(LeaderboardService.KEY))
                .as("a miss should populate the cache, not just fall through to SQL")
                .isTrue();
    }

    @Test
    @DisplayName("ranks by rating, highest first, with dense 1-based positions")
    void ordersByRating() {
        List<LeaderboardEntryResponse> top = leaderboardService.top(10);

        assertThat(top).extracting(LeaderboardEntryResponse::rating)
                .isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(top).extracting(LeaderboardEntryResponse::rank)
                .containsExactlyElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, top.size()).boxed().toList());
        assertThat(top.get(0).username()).isEqualTo("admin");
    }

    @Test
    @DisplayName("attaches solve counts from PostgreSQL rather than caching them")
    void carriesSolveCounts() {
        LeaderboardEntryResponse carol = leaderboardService.top(10).stream()
                .filter(entry -> entry.username().equals("carol"))
                .findFirst()
                .orElseThrow();

        // Solve counts live in one place. Caching them too would be a second copy of a mutable
        // number, free to disagree with the first.
        assertThat(carol.solvedCount()).isPositive();
    }

    @Test
    @DisplayName("honours the requested size")
    void respectsLimit() {
        assertThat(leaderboardService.top(2)).hasSize(2);
    }

    @Test
    @DisplayName("answers a user's rank without scanning the whole table")
    void rankLookup() {
        // ZREVRANK is O(log N) for any user at any position - the operation that justifies
        // keeping a sorted set at all, since the SQL equivalent ranks everybody to find one.
        OptionalInt adminRank = leaderboardService.rankOf("admin");
        OptionalInt aliceRank = leaderboardService.rankOf("alice");

        assertThat(adminRank).hasValue(1);
        assertThat(aliceRank.getAsInt()).isGreaterThan(adminRank.getAsInt());
    }

    @Test
    @DisplayName("an unranked user has no rank rather than a wrong one")
    void unknownUserHasNoRank() {
        leaderboardService.rebuild();

        assertThat(leaderboardService.rankOf("nobody-at-all")).isEmpty();
    }

    @Test
    @DisplayName("a recorded rating change moves the user")
    void recordUpdatesRanking() {
        leaderboardService.rebuild();
        assertThat(leaderboardService.rankOf("alice").getAsInt()).isGreaterThan(1);

        leaderboardService.record("alice", 4000);

        assertThat(leaderboardService.rankOf("alice")).hasValue(1);
    }

    @Test
    @DisplayName("rebuilding twice is idempotent")
    void rebuildIsIdempotent() {
        int first = leaderboardService.rebuild();
        int second = leaderboardService.rebuild();

        assertThat(first).isEqualTo(second);
        assertThat(redis.opsForZSet().zCard(LeaderboardService.KEY)).isEqualTo(first);
    }
}
