package com.codearena.api.service;

import com.codearena.api.domain.Problem;
import com.codearena.api.domain.Tag;
import com.codearena.api.domain.User;
import com.codearena.api.domain.UserTagStats;
import com.codearena.api.recommendation.Candidate;
import com.codearena.api.recommendation.PrerequisiteGate;
import com.codearena.api.recommendation.RecommendationEngine;
import com.codearena.api.recommendation.RecommendationProperties;
import com.codearena.api.recommendation.ScoreBreakdown;
import com.codearena.api.recommendation.ScoredCandidate;
import com.codearena.api.recommendation.TagProficiency;
import com.codearena.api.repository.ProblemRepository;
import com.codearena.api.repository.SubmissionRepository;
import com.codearena.api.repository.TagRepository;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.repository.UserTagStatsRepository;
import com.codearena.api.web.dto.RecommendationResponse;
import com.codearena.api.web.error.ResourceNotFoundException;
import com.codearena.api.web.mapper.ProblemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Adapts the database to the pure {@link RecommendationEngine} and back.
 *
 * <p>All the I/O lives here so the engine stays framework-free: four queries in, plain values
 * across the boundary, DTOs out. The queries are fixed in number regardless of how many
 * candidates come back - there is no per-problem lookup anywhere in this method, which is the
 * difference between one request and forty.
 */
@Service
@Transactional(readOnly = true)
public class RecommendationService {

    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final SubmissionRepository submissionRepository;
    private final UserTagStatsRepository userTagStatsRepository;
    private final TagRepository tagRepository;
    private final RecommendationEngine engine;
    private final RecommendationProperties properties;
    private final ProblemMapper problemMapper;
    private final Clock clock;

    public RecommendationService(UserRepository userRepository,
                                 ProblemRepository problemRepository,
                                 SubmissionRepository submissionRepository,
                                 UserTagStatsRepository userTagStatsRepository,
                                 TagRepository tagRepository,
                                 RecommendationEngine engine,
                                 RecommendationProperties properties,
                                 ProblemMapper problemMapper,
                                 Clock clock) {
        this.userRepository = userRepository;
        this.problemRepository = problemRepository;
        this.submissionRepository = submissionRepository;
        this.userTagStatsRepository = userTagStatsRepository;
        this.tagRepository = tagRepository;
        this.engine = engine;
        this.properties = properties;
        this.problemMapper = problemMapper;
        this.clock = clock;
    }

    public List<RecommendationResponse> recommendFor(String username, int limit) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User", username));

        TagProficiency proficiency = loadProficiency(user.getId());
        PrerequisiteGate gate = PrerequisiteGate.build(
                loadPrerequisiteDag(), proficiency,
                properties.masteryFloor(), properties.minEvidenceAttempts());

        Set<Long> solved = submissionRepository.findSolvedProblemIds(user.getId());
        Map<Long, Integer> attemptsByProblem = loadAttemptCounts(user.getId());

        List<Problem> pool = problemRepository.findCandidatesExcludingSolved(
                user.getRating() - properties.ratingBandBelow(),
                user.getRating() + properties.ratingBandAbove(),
                solved);

        List<Candidate> candidates = pool.stream()
                .map(problem -> toCandidate(problem, attemptsByProblem))
                .toList();

        Map<Long, Problem> byId = pool.stream()
                .collect(Collectors.toMap(Problem::getId, Function.identity()));

        return engine.recommend(candidates, user.getRating(), proficiency, gate, limit, clock.instant())
                .stream()
                .map(scored -> toResponse(scored, byId.get(scored.candidate().problemId())))
                .toList();
    }

    /**
     * Carries the raw counts across the boundary, not a precomputed proficiency: the gate and
     * the scorer ask different questions of them. See {@link TagProficiency}.
     */
    private TagProficiency loadProficiency(Long userId) {
        Map<String, TagProficiency.TagRecord> byTag = new HashMap<>();
        for (UserTagStats stats : userTagStatsRepository.findByUserId(userId)) {
            byTag.put(stats.getTag().getName(),
                    new TagProficiency.TagRecord(stats.getSolvedCount(), stats.getAttemptCount()));
        }
        return new TagProficiency(byTag, ProficiencyScoring.DEFAULT_SMOOTHING);
    }

    /**
     * The whole prerequisite DAG in one query. It is 30 nodes and 36 edges and changes about
     * never, so loading it per request costs nothing worth caching.
     */
    private Map<String, Set<String>> loadPrerequisiteDag() {
        Map<String, Set<String>> dag = new HashMap<>();
        for (Tag tag : tagRepository.findAllWithPrerequisites()) {
            dag.put(tag.getName(), tag.getPrerequisites().stream()
                    .map(Tag::getName)
                    .collect(Collectors.toSet()));
        }
        return dag;
    }

    private Map<Long, Integer> loadAttemptCounts(Long userId) {
        Map<Long, Integer> attempts = new HashMap<>();
        for (Object[] row : submissionRepository.countAttemptsPerProblem(userId)) {
            attempts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return attempts;
    }

    private Candidate toCandidate(Problem problem, Map<Long, Integer> attemptsByProblem) {
        return new Candidate(
                problem.getId(),
                problem.getRating(),
                problem.getTags().stream().map(Tag::getName).collect(Collectors.toSet()),
                problem.getCreatedAt(),
                attemptsByProblem.getOrDefault(problem.getId(), 0));
    }

    private RecommendationResponse toResponse(ScoredCandidate scored, Problem problem) {
        ScoreBreakdown breakdown = scored.breakdown();
        return new RecommendationResponse(
                problemMapper.toSummary(problem),
                round(breakdown.total()),
                explain(breakdown, scored.candidate().tags(), problem),
                new RecommendationResponse.WhyResponse(
                        round(breakdown.tagWeakness()),
                        round(breakdown.ratingFit()),
                        round(breakdown.recency()),
                        round(breakdown.repetitionPenalty())));
    }

    /**
     * Turns the dominant term into a sentence.
     *
     * <p>Picking the single largest contributor rather than listing all four: "targets a weak
     * topic" is something a user acts on, whereas four decimals is something they scroll past.
     * The full breakdown is still in the payload for anyone who wants it.
     */
    private String explain(ScoreBreakdown breakdown, Set<String> tags, Problem problem) {
        String weakestTag = tags.stream().min(Comparator.naturalOrder()).orElse("this topic");

        double weaknessContribution = properties.weightTagWeakness() * breakdown.tagWeakness();
        double fitContribution = properties.weightRatingFit() * breakdown.ratingFit();

        if (breakdown.repetitionPenalty() > 0.5) {
            return "revisit " + weakestTag + " - you have bounced off this one before";
        }
        if (weaknessContribution >= fitContribution) {
            return "targets a weak topic (" + weakestTag + ")";
        }
        return "well matched to your rating at " + problem.getRating();
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
