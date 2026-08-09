package com.codearena.api.recommendation;

import java.util.Map;
import java.util.Set;

/**
 * A user's per-topic record.
 *
 * <h2>Two questions, two measures</h2>
 *
 * <p>Ranking asks <em>"how weak is this topic?"</em> and wants the smoothed ratio
 * {@code solved / (attempts + k)}, which deliberately pulls thin evidence towards zero so one
 * lucky solve cannot retire a topic from the recommendation pool.
 *
 * <p>Gating asks <em>"has this topic been demonstrably failed?"</em> and must <strong>not</strong>
 * use that number. Smoothing makes "attempted once, solved it" score 0.25 - below any sensible
 * mastery threshold - so a perfect record reads as a failure. Applied to a root topic that
 * verdict cascades through the whole taxonomy and gates almost everything out. Gating therefore
 * uses the raw ratio and refuses to judge at all until there is enough evidence to judge on.
 *
 * <p>That distinction is the whole reason this type holds counts rather than a precomputed
 * double: the two callers genuinely need to ask different questions of the same data.
 */
public record TagProficiency(Map<String, TagRecord> recordsByTag, double smoothing) {

    /** Assumed proficiency for a topic with no history, used only for scoring. */
    private static final double UNKNOWN_PROFICIENCY = 0.0;

    public TagProficiency {
        recordsByTag = Map.copyOf(recordsByTag);
    }

    public static TagProficiency empty(double smoothing) {
        return new TagProficiency(Map.of(), smoothing);
    }

    /**
     * @param solved   distinct problems with this tag the user has solved
     * @param attempts distinct problems with this tag the user has attempted
     */
    public record TagRecord(int solved, int attempts) {

        public TagRecord {
            // The database already enforces this (ck_user_tag_stats_counts), but the invariant
            // is what makes every ratio here land in [0, 1] - and a violation produces a
            // "proficiency" above 1, which then reads as *negative* weakness and quietly
            // inverts the ranking rather than failing. Caught exactly that in a test fixture.
            if (solved < 0 || attempts < 0 || solved > attempts) {
                throw new IllegalArgumentException(
                        "invalid tag record: solved=" + solved + ", attempts=" + attempts);
            }
        }

        /** Conservative estimate for ranking; see the class javadoc. */
        public double smoothed(double smoothing) {
            return solved / (attempts + smoothing);
        }

        /** Unsmoothed success rate, for threshold decisions. Zero when never attempted. */
        public double rawSuccessRatio() {
            return attempts == 0 ? 0.0 : (double) solved / attempts;
        }
    }

    public boolean hasAttempted(String tag) {
        return recordsByTag.containsKey(tag);
    }

    /**
     * Treats an untouched topic as zero proficiency <em>for scoring</em>, which makes it look
     * maximally weak and therefore worth recommending. That is the intent: a topic you have
     * never tried is exactly what "what should I solve next" should surface.
     */
    public double proficiencyOf(String tag) {
        TagRecord record = recordsByTag.get(tag);
        return record == null ? UNKNOWN_PROFICIENCY : record.smoothed(smoothing);
    }

    /**
     * Whether the user has demonstrably <em>not</em> learned a topic.
     *
     * <p>Requires both a low raw success rate and enough attempts to mean anything. Below
     * {@code minEvidenceAttempts} the answer is "we do not know yet", which is not the same as
     * "no" and must not be treated as one.
     */
    public boolean isDemonstrablyWeak(String tag, double floor, int minEvidenceAttempts) {
        TagRecord record = recordsByTag.get(tag);
        if (record == null || record.attempts() < minEvidenceAttempts) {
            return false;
        }
        return record.rawSuccessRatio() < floor;
    }

    /**
     * How weak the user is across a problem's topics, in [0, 1].
     *
     * <p>The mean rather than the minimum. Taking the weakest tag would score every multi-topic
     * problem as maximally weak the moment it touched one unfamiliar area, so a hard graph
     * problem tagged {@code graph, bfs, implementation} would outrank a focused one - the
     * opposite of useful.
     */
    public double weaknessAcross(Set<String> tags) {
        if (tags.isEmpty()) {
            return 0.0;
        }
        double total = 0.0;
        for (String tag : tags) {
            total += 1.0 - proficiencyOf(tag);
        }
        return total / tags.size();
    }
}
