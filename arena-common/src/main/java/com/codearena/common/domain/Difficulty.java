package com.codearena.common.domain;

/**
 * Coarse difficulty bucket shown in the UI. The numeric {@code rating} on a problem is the
 * value the recommendation engine actually scores against; this enum exists for filtering
 * and display.
 */
public enum Difficulty {
    EASY,
    MEDIUM,
    HARD;

    private static final int MEDIUM_FLOOR = 1200;
    private static final int HARD_FLOOR = 1700;

    /**
     * Derives the bucket from the numeric rating.
     *
     * <p>Difficulty is never accepted from a client: it is a projection of {@code rating}, so
     * letting callers supply both invites a problem labelled EASY at rating 2200. The
     * equivalent invariant is asserted against the seed data by {@code SeedDataIT}.
     *
     * @throws IllegalArgumentException if the rating falls outside the supported 0-4000 range
     *                                  enforced by the {@code ck_problems_rating} constraint
     */
    public static Difficulty fromRating(int rating) {
        if (rating < 0 || rating > 4000) {
            throw new IllegalArgumentException("rating must be between 0 and 4000, was " + rating);
        }
        if (rating < MEDIUM_FLOOR) {
            return EASY;
        }
        return rating < HARD_FLOOR ? MEDIUM : HARD;
    }
}
