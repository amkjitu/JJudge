package com.codearena.api.recommendation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Selects the problems a user should attempt next.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Drop candidates whose every topic is gated by an unmet prerequisite.</li>
 *   <li>Score each survivor.</li>
 *   <li>Keep the best {@code K = n · overfetch} with a bounded min-heap.</li>
 *   <li>Walk them best-first, taking results while no topic exceeds its diversity cap.</li>
 * </ol>
 *
 * <h2>Complexity</h2>
 * <p>With {@code M} candidates, {@code n} requested, {@code t} the mean tags per problem and
 * {@code K = n · overfetch}:
 * <pre>
 *   gate + score      O(M · t)
 *   top-K selection   O(M log K)
 *   diversify         O(K log K)
 *   total             O(M log K)
 * </pre>
 *
 * <h2>Why a bounded heap rather than sorting</h2>
 * <p>Sorting the whole pool is O(M log M) and allocates an array of every candidate. The heap
 * holds at most K entries and compares each candidate against its minimum, giving O(M log K)
 * with O(K) memory. For the seeded catalogue the difference is unmeasurable; the reason to
 * write it this way is that {@code M} grows with the catalogue while {@code K} is fixed at
 * roughly thirty - so the gap widens exactly as it starts to matter, and the code does not need
 * revisiting when it does.
 *
 * <h2>Why over-fetch before diversifying</h2>
 * <p>A heap of exactly {@code n} yields the top {@code n} by score, and the diversity cap then
 * removes some of them - leaving fewer than were asked for, with no way to backfill. Keeping
 * {@code n · overfetch} means the cap has alternatives to promote. It is still O(M log K),
 * since the factor sits inside the logarithm.
 */
public final class RecommendationEngine {

    private final ProblemScorer scorer;
    private final RecommendationProperties properties;

    public RecommendationEngine(ProblemScorer scorer, RecommendationProperties properties) {
        this.scorer = scorer;
        this.properties = properties;
    }

    /**
     * @param limit how many recommendations to return; fewer come back if the pool is thin
     */
    public List<ScoredCandidate> recommend(List<Candidate> candidates,
                                           int userRating,
                                           TagProficiency proficiency,
                                           PrerequisiteGate gate,
                                           int limit,
                                           Instant now) {
        if (candidates.isEmpty() || limit <= 0) {
            return List.of();
        }

        List<Candidate> permitted = applyGate(candidates, gate);
        int keep = Math.max(limit, limit * properties.overfetchFactor());
        List<ScoredCandidate> best = selectTop(permitted, keep, userRating, proficiency, now);

        return diversify(best, limit);
    }

    /**
     * Drops gated candidates, but never returns an empty pool when a non-empty one went in.
     *
     * <p>The fallback matters: a user weak at a root topic could otherwise have every single
     * candidate blocked and be shown nothing at all. An empty panel is a worse answer than a
     * slightly-too-hard one, so in that case the gate stands down and scoring decides.
     */
    private List<Candidate> applyGate(List<Candidate> candidates, PrerequisiteGate gate) {
        List<Candidate> permitted = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (!gate.blocksProblem(candidate.tags())) {
                permitted.add(candidate);
            }
        }
        return permitted.isEmpty() ? candidates : permitted;
    }

    /**
     * Bounded min-heap: the root is always the weakest of the best {@code keep} seen so far, so
     * a new candidate is compared against it in O(1) and only inserted - O(log keep) - when it
     * would displace it.
     */
    private List<ScoredCandidate> selectTop(List<Candidate> candidates,
                                            int keep,
                                            int userRating,
                                            TagProficiency proficiency,
                                            Instant now) {
        PriorityQueue<ScoredCandidate> heap =
                new PriorityQueue<>(Math.min(keep, candidates.size()) + 1,
                        ScoredCandidate.BY_SCORE_THEN_ID);

        for (Candidate candidate : candidates) {
            ScoredCandidate scored = new ScoredCandidate(candidate,
                    scorer.score(candidate, userRating, proficiency, now));

            if (heap.size() < keep) {
                heap.offer(scored);
            } else if (ScoredCandidate.BY_SCORE_THEN_ID.compare(scored, heap.peek()) > 0) {
                heap.poll();
                heap.offer(scored);
            }
        }

        List<ScoredCandidate> best = new ArrayList<>(heap);
        best.sort(ScoredCandidate.BY_SCORE_THEN_ID.reversed());
        return best;
    }

    /**
     * Greedy best-first selection subject to a per-topic cap.
     *
     * <p>Greedy rather than optimal on purpose. Choosing the highest-scoring set that satisfies
     * the caps is a constrained selection problem; taking them in score order and skipping those
     * that would breach a cap is O(K·t), gives an obviously explainable result ("the best ones,
     * spread out"), and no user could tell the difference from the optimum.
     *
     * <p>If the cap leaves fewer than {@code limit}, the skipped candidates are added back in
     * score order. Returning eight when ten were asked for, purely to honour a soft preference
     * about variety, would be the cap overruling the actual request.
     */
    private List<ScoredCandidate> diversify(List<ScoredCandidate> ranked, int limit) {
        Map<String, Integer> perTag = new HashMap<>();
        List<ScoredCandidate> selected = new ArrayList<>(limit);
        List<ScoredCandidate> deferred = new ArrayList<>();

        for (ScoredCandidate scored : ranked) {
            if (selected.size() == limit) {
                break;
            }
            if (exceedsCap(scored.candidate().tags(), perTag)) {
                deferred.add(scored);
                continue;
            }
            selected.add(scored);
            scored.candidate().tags().forEach(tag -> perTag.merge(tag, 1, Integer::sum));
        }

        for (ScoredCandidate scored : deferred) {
            if (selected.size() == limit) {
                break;
            }
            selected.add(scored);
        }

        // Backfilled entries were appended out of order; restore a strict score ranking.
        selected.sort(Comparator.comparingDouble(ScoredCandidate::total).reversed()
                .thenComparingLong(s -> s.candidate().problemId()));
        return List.copyOf(selected);
    }

    private boolean exceedsCap(Set<String> tags, Map<String, Integer> perTag) {
        int cap = properties.maxPerTag();
        for (String tag : tags) {
            if (perTag.getOrDefault(tag, 0) >= cap) {
                return true;
            }
        }
        return false;
    }
}
