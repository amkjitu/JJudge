package com.codearena.common.domain;

/**
 * Coarse difficulty bucket shown in the UI. The numeric {@code rating} on a problem is the
 * value the recommendation engine actually scores against; this enum exists for filtering
 * and display.
 */
public enum Difficulty {
    EASY,
    MEDIUM,
    HARD
}
