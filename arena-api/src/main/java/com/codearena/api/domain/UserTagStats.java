package com.codearena.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Denormalised counters behind the tag-proficiency vector.
 *
 * <p>Counted at problem granularity: five failed submissions on one problem is one attempt.
 * Otherwise {@code solved / attempts} would measure persistence rather than proficiency.
 */
@Entity
@Table(name = "user_tag_stats")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserTagStats {

    @EmbeddedId
    private UserTagStatsId id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @MapsId("tagId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false)
    private Tag tag;

    @Column(name = "solved_count", nullable = false)
    @Builder.Default
    private Integer solvedCount = 0;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    /**
     * Smoothed proficiency in [0, 1). The {@code + smoothing} term is a Bayesian prior that
     * stops "1 solved out of 1 attempt" from outranking "18 solved out of 20".
     *
     * @param smoothing pseudo-count k; larger values demand more evidence before a tag counts
     *                  as mastered
     */
    public double proficiency(double smoothing) {
        return solvedCount / (attemptCount + smoothing);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserTagStats other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return UserTagStats.class.hashCode();
    }
}
