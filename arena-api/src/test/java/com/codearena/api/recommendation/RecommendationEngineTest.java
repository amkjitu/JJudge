package com.codearena.api.recommendation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RecommendationEngine")
class RecommendationEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private static RecommendationProperties properties(Integer maxPerTag, Integer overfetch) {
        return new RecommendationProperties(null, null, null, null, null, null,
                null, null, null, null, null, null, maxPerTag, overfetch);
    }

    private static RecommendationEngine engine(RecommendationProperties props) {
        return new RecommendationEngine(new ProblemScorer(props), props);
    }

    private static RecommendationEngine defaultEngine() {
        return engine(properties(null, null));
    }

    private static Candidate candidate(long id, int rating, Set<String> tags) {
        return new Candidate(id, rating, tags, NOW.minus(Duration.ofDays(30)), 0);
    }

    @Nested
    @DisplayName("selection")
    class Selection {

        @Test
        @DisplayName("returns nothing for an empty pool or a non-positive limit")
        void degenerateInputs() {
            assertThat(defaultEngine().recommend(List.of(), 1200, TagProficiency.empty(3.0),
                    PrerequisiteGate.open(), 5, NOW)).isEmpty();

            assertThat(defaultEngine().recommend(List.of(candidate(1, 1300, Set.of("dp"))),
                    1200, TagProficiency.empty(3.0), PrerequisiteGate.open(), 0, NOW)).isEmpty();
        }

        @Test
        @DisplayName("never returns more than the requested limit")
        void respectsLimit() {
            List<Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                pool.add(candidate(i, 1250 + i, Set.of("tag" + (i % 9))));
            }

            assertThat(defaultEngine().recommend(pool, 1200, TagProficiency.empty(3.0),
                    PrerequisiteGate.open(), 5, NOW)).hasSize(5);
        }

        @Test
        @DisplayName("returns the whole pool when it is smaller than the limit")
        void thinPool() {
            List<Candidate> pool = List.of(
                    candidate(1, 1300, Set.of("dp")),
                    candidate(2, 1250, Set.of("graph")));

            assertThat(defaultEngine().recommend(pool, 1200, TagProficiency.empty(3.0),
                    PrerequisiteGate.open(), 10, NOW)).hasSize(2);
        }

        @Test
        @DisplayName("results come back in descending score order")
        void orderedByScore() {
            List<Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                pool.add(candidate(i, 1100 + i * 20, Set.of("tag" + i)));
            }

            List<ScoredCandidate> result = defaultEngine().recommend(pool, 1200,
                    TagProficiency.empty(3.0), PrerequisiteGate.open(), 8, NOW);

            assertThat(result).extracting(ScoredCandidate::total).isSortedAccordingTo(
                    Comparator.reverseOrder());
        }

        @Test
        @DisplayName("ties break deterministically, so the same request gives the same list")
        void deterministicUnderTies() {
            // Identical rating, identical tag, identical age: every score is equal, so only the
            // tie-break makes the output stable.
            List<Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                pool.add(candidate(i, 1300, Set.of("dp")));
            }

            List<Long> first = ids(defaultEngine().recommend(pool, 1200, TagProficiency.empty(3.0),
                    PrerequisiteGate.open(), 5, NOW));
            List<Long> second = ids(defaultEngine().recommend(pool, 1200, TagProficiency.empty(3.0),
                    PrerequisiteGate.open(), 5, NOW));

            assertThat(first).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("bounded heap correctness")
    class HeapCorrectness {

        /**
         * The heap is an optimisation, and an optimisation that changes the answer is a bug.
         * This compares it against the obvious O(M log M) implementation over a large random
         * pool - if the two ever disagree, the heap is wrong.
         */
        @Test
        @DisplayName("agrees with a full sort over 100k random candidates")
        void matchesFullSortReference() {
            RecommendationProperties props = properties(Integer.MAX_VALUE, 1);
            List<Candidate> pool = randomPool(100_000, new Random(20260801L));
            TagProficiency proficiency = proficiencyOf(Map.of("dp", new int[]{1, 4}, "graph", new int[]{7, 10}));
            int limit = 25;

            List<ScoredCandidate> viaHeap = engine(props).recommend(
                    pool, 1500, proficiency, PrerequisiteGate.open(), limit, NOW);

            ProblemScorer scorer = new ProblemScorer(props);
            List<ScoredCandidate> viaSort = pool.stream()
                    .map(c -> new ScoredCandidate(c, scorer.score(c, 1500, proficiency, NOW)))
                    .sorted(ScoredCandidate.BY_SCORE_THEN_ID.reversed())
                    .limit(limit)
                    .toList();

            assertThat(ids(viaHeap)).isEqualTo(ids(viaSort));
        }

        @Test
        @DisplayName("keeps the true best when the winner arrives last")
        void winnerAtTheEnd() {
            List<Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                pool.add(candidate(i, 2400, Set.of("arrays")));   // far off target
            }
            pool.add(candidate(999, 1300, Set.of("geometry")));   // perfect fit, untouched topic

            List<ScoredCandidate> result = engine(properties(Integer.MAX_VALUE, 1)).recommend(
                    pool, 1200, proficiencyOf(Map.of("arrays", new int[]{19, 20})),
                    PrerequisiteGate.open(), 3, NOW);

            assertThat(result.get(0).candidate().problemId()).isEqualTo(999L);
        }

        @Test
        @DisplayName("handles a pool far larger than the heap without exhausting memory")
        void largePoolStaysBounded() {
            List<Candidate> pool = randomPool(200_000, new Random(7L));

            List<ScoredCandidate> result = defaultEngine().recommend(pool, 1500,
                    TagProficiency.empty(3.0), PrerequisiteGate.open(), 10, NOW);

            assertThat(result).hasSize(10);
        }
    }

    @Nested
    @DisplayName("diversity cap")
    class Diversity {

        @Test
        @DisplayName("caps how many results may share a topic")
        void capsPerTag() {
            // Twenty dp problems and a handful of others: without the cap the whole list would
            // be dp, which is not practice, it is a rut.
            List<Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                pool.add(candidate(i, 1300, Set.of("dp")));
            }
            for (int i = 20; i < 26; i++) {
                pool.add(candidate(i, 1300, Set.of("graph")));
            }

            List<ScoredCandidate> result = engine(properties(2, 3)).recommend(pool, 1200,
                    TagProficiency.empty(3.0), PrerequisiteGate.open(), 6, NOW);

            long dpCount = result.stream()
                    .filter(s -> s.candidate().tags().contains("dp"))
                    .count();
            assertThat(dpCount).isEqualTo(2);
        }

        @Test
        @DisplayName("still returns the full limit when the cap would otherwise starve the list")
        void backfillsRatherThanReturningTooFew() {
            // Every problem is dp, so a strict cap of 2 could only yield 2. Returning 2 when 6
            // were asked for would be the cap overruling the request.
            List<Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                pool.add(candidate(i, 1300, Set.of("dp")));
            }

            List<ScoredCandidate> result = engine(properties(2, 3)).recommend(pool, 1200,
                    TagProficiency.empty(3.0), PrerequisiteGate.open(), 6, NOW);

            assertThat(result).hasSize(6);
        }

        @Test
        @DisplayName("backfilled results are still ordered by score")
        void backfillPreservesOrdering() {
            List<Candidate> pool = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                pool.add(candidate(i, 1300 + i, Set.of("dp")));
            }

            List<ScoredCandidate> result = engine(properties(2, 3)).recommend(pool, 1200,
                    TagProficiency.empty(3.0), PrerequisiteGate.open(), 6, NOW);

            assertThat(result).extracting(ScoredCandidate::total)
                    .isSortedAccordingTo(Comparator.reverseOrder());
        }
    }

    @Nested
    @DisplayName("prerequisite gating")
    class Gating {

        private static final Map<String, Set<String>> DAG = Map.of(
                "arrays", Set.of("implementation"),
                "segment-tree", Set.of("arrays"));

        @Test
        @DisplayName("filters out problems whose topics are all gated")
        void gatedProblemsAreDropped() {
            PrerequisiteGate gate = PrerequisiteGate.build(
                    DAG, proficiencyOf(Map.of("arrays", new int[]{0, 10})), 0.34, 3);

            List<Candidate> pool = List.of(
                    candidate(1, 1300, Set.of("segment-tree")),
                    candidate(2, 1300, Set.of("implementation")));

            List<ScoredCandidate> result = defaultEngine().recommend(pool, 1200,
                    proficiencyOf(Map.of("arrays", new int[]{0, 10})), gate, 5, NOW);

            assertThat(ids(result)).containsExactly(2L);
        }

        @Test
        @DisplayName("stands down rather than returning an empty list")
        void gateNeverEmptiesTheList() {
            // Every candidate is gated. An empty panel is a worse answer than a hard problem,
            // so the gate yields to scoring rather than leaving the user with nothing.
            PrerequisiteGate gate = PrerequisiteGate.build(
                    DAG, proficiencyOf(Map.of("arrays", new int[]{0, 10})), 0.34, 3);

            List<Candidate> pool = List.of(candidate(1, 1300, Set.of("segment-tree")));

            List<ScoredCandidate> result = defaultEngine().recommend(pool, 1200,
                    proficiencyOf(Map.of("arrays", new int[]{0, 10})), gate, 5, NOW);

            assertThat(ids(result)).containsExactly(1L);
        }
    }

    private static TagProficiency proficiencyOf(Map<String, int[]> counts) {
        Map<String, TagProficiency.TagRecord> records = new HashMap<>();
        counts.forEach((tag, sa) -> records.put(tag, new TagProficiency.TagRecord(sa[0], sa[1])));
        return new TagProficiency(records, 3.0);
    }

    private static List<Long> ids(List<ScoredCandidate> scored) {
        return scored.stream().map(s -> s.candidate().problemId()).toList();
    }

    private static List<Candidate> randomPool(int size, Random random) {
        String[] tags = {"dp", "graph", "arrays", "strings", "math", "tree", "greedy"};
        List<Candidate> pool = new ArrayList<>(size);
        Map<Integer, Set<String>> tagCache = new HashMap<>();

        for (int i = 0; i < size; i++) {
            int tagKey = random.nextInt(tags.length * tags.length);
            // A LinkedHashSet, not Set.of: the two draws frequently coincide, and Set.of
            // rejects duplicates rather than collapsing them.
            Set<String> problemTags = tagCache.computeIfAbsent(tagKey, k -> {
                Set<String> chosen = new LinkedHashSet<>();
                chosen.add(tags[k / tags.length]);
                chosen.add(tags[k % tags.length]);
                return Set.copyOf(chosen);
            });

            pool.add(new Candidate(
                    i,
                    800 + random.nextInt(1600),
                    problemTags,
                    NOW.minus(Duration.ofDays(random.nextInt(1000))),
                    random.nextInt(4)));
        }
        return pool;
    }
}
