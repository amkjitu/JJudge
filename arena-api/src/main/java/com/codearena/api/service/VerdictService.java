package com.codearena.api.service;

import com.codearena.api.domain.Submission;
import com.codearena.api.domain.User;
import com.codearena.api.repository.SubmissionRepository;
import com.codearena.api.repository.UserRepository;
import com.codearena.api.repository.UserTagStatsRepository;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import com.codearena.common.event.VerdictAssigned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Writes a verdict back and updates everything derived from it.
 *
 * <p>This is where the recommendation engine's inputs are maintained: {@code user_tag_stats} is
 * the tag-proficiency vector, and if it is not updated here it silently stops reflecting reality
 * and the recommender quietly degrades into "sort the catalogue by rating".
 */
@Service
public class VerdictService {

    private static final Logger log = LoggerFactory.getLogger(VerdictService.class);

    /**
     * Elo K-factor, treating the problem as an opponent the user either beat or did not.
     *
     * <p>32 is the conventional starting value: large enough that a handful of solves moves a
     * new account towards its real level, small enough that one lucky solve does not.
     */
    private static final int ELO_K = 32;

    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final UserTagStatsRepository userTagStatsRepository;
    private final LeaderboardService leaderboardService;

    public VerdictService(SubmissionRepository submissionRepository,
                          UserRepository userRepository,
                          UserTagStatsRepository userTagStatsRepository,
                          LeaderboardService leaderboardService) {
        this.submissionRepository = submissionRepository;
        this.userRepository = userRepository;
        this.userTagStatsRepository = userTagStatsRepository;
        this.leaderboardService = leaderboardService;
    }

    /**
     * Applies a verdict.
     *
     * <p><strong>Idempotent.</strong> Kafka guarantees at-least-once delivery, so this method
     * will be called twice for the same submission sooner or later - after a rebalance, or a
     * redelivery following a commit that did not land. Without the guard, the second call would
     * increment the tag counters and the user's rating all over again, so a duplicated message
     * would quietly inflate someone's standing.
     *
     * @return the updated submission, or empty when the event was a duplicate or the submission
     *         no longer exists
     */
    @Transactional
    public Optional<Submission> apply(VerdictAssigned event) {
        Optional<Submission> found = submissionRepository.findWithDetailsById(event.submissionId());
        if (found.isEmpty()) {
            // The row can legitimately be gone: an admin deleted the problem, which cascades.
            log.warn("Verdict for unknown submission {}, ignoring", event.submissionId());
            return Optional.empty();
        }

        Submission submission = found.get();
        if (submission.getStatus() == SubmissionStatus.DONE) {
            log.debug("Submission {} already judged as {}; ignoring duplicate verdict",
                    submission.getId(), submission.getVerdict());
            return Optional.empty();
        }

        // Read before writing: once this submission is marked DONE it becomes part of its own
        // history, and "was this the first attempt?" would answer itself wrongly.
        Long userId = submission.getUser().getId();
        Long problemId = submission.getProblem().getId();
        boolean firstJudgedAttempt = submissionRepository
                .countByUserIdAndProblemIdAndStatus(userId, problemId, SubmissionStatus.DONE) == 0;
        boolean alreadySolved = submissionRepository
                .existsByUserIdAndProblemIdAndVerdict(userId, problemId, Verdict.AC);

        submission.setStatus(SubmissionStatus.DONE);
        submission.setVerdict(event.verdict());
        submission.setRuntimeMs(event.runtimeMs());

        boolean newlySolved = event.verdict() == Verdict.AC && !alreadySolved;
        updateTagStats(userId, problemId, firstJudgedAttempt, newlySolved);

        if (newlySolved) {
            awardRating(submission.getUser(), submission.getProblem().getRating());
        }

        log.info("Submission {} judged {} ({}/{} cases){}", submission.getId(), event.verdict(),
                event.testsPassed(), event.testsTotal(), newlySolved ? " - first solve" : "");

        return Optional.of(submission);
    }

    /**
     * Counters are per <em>problem</em>, not per submission: the third failed attempt at one
     * problem must not count as a third attempt at the topic, or proficiency would measure
     * persistence rather than skill. That is why both flags are computed from prior history
     * rather than incremented unconditionally.
     */
    private void updateTagStats(Long userId, Long problemId, boolean firstAttempt, boolean newlySolved) {
        int attemptDelta = firstAttempt ? 1 : 0;
        int solvedDelta = newlySolved ? 1 : 0;

        if (attemptDelta == 0 && solvedDelta == 0) {
            return;
        }
        userTagStatsRepository.applyDeltas(userId, problemId, solvedDelta, attemptDelta);
    }

    /**
     * Elo against the problem's rating, applied only on a first solve.
     *
     * <p>Solving something far above your level moves you a lot; clearing an easy problem barely
     * registers. Failures cost nothing on purpose - this is a practice platform, and a rating
     * that punishes attempting hard problems trains people to avoid them, which is the opposite
     * of the point.
     */
    private void awardRating(User user, int problemRating) {
        double expected = 1.0 / (1.0 + Math.pow(10.0, (problemRating - user.getRating()) / 400.0));
        int delta = (int) Math.round(ELO_K * (1.0 - expected));
        int updated = Math.max(0, Math.min(4000, user.getRating() + delta));

        user.setRating(updated);
        userRepository.save(user);
        leaderboardService.record(user.getUsername(), updated);
    }
}
