package com.codearena.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Difficulty.fromRating")
class DifficultyTest {

    @ParameterizedTest(name = "rating {0} -> {1}")
    @CsvSource({
            "0,    EASY",
            "800,  EASY",
            "1199, EASY",
            "1200, MEDIUM",
            "1450, MEDIUM",
            "1699, MEDIUM",
            "1700, HARD",
            "2200, HARD",
            "4000, HARD"
    })
    @DisplayName("maps ratings onto buckets")
    void mapsRatings(int rating, Difficulty expected) {
        assertThat(Difficulty.fromRating(rating)).isEqualTo(expected);
    }

    @Test
    @DisplayName("boundaries are inclusive-below, exclusive-above")
    void boundariesDoNotOverlap() {
        assertThat(Difficulty.fromRating(1199)).isEqualTo(Difficulty.EASY);
        assertThat(Difficulty.fromRating(1200)).isEqualTo(Difficulty.MEDIUM);
        assertThat(Difficulty.fromRating(1699)).isEqualTo(Difficulty.MEDIUM);
        assertThat(Difficulty.fromRating(1700)).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("rejects ratings outside the range the database allows")
    void rejectsOutOfRange() {
        assertThatThrownBy(() -> Difficulty.fromRating(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0 and 4000");

        assertThatThrownBy(() -> Difficulty.fromRating(4001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
