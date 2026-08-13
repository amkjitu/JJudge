package com.codearena.api.domain;

import com.codearena.common.domain.JudgingMethod;
import com.codearena.common.domain.Language;
import com.codearena.common.domain.SubmissionStatus;
import com.codearena.common.domain.Verdict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * A single attempt at a problem. The submitted source code itself lives in MongoDB
 * (Phase 7) keyed by this row's id - relational storage holds only the metadata the
 * leaderboard, stats and recommender need to query.
 */
@Entity
@Table(name = "submissions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Submission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Language language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubmissionStatus status = SubmissionStatus.QUEUED;

    /** Null until judging completes; the DB enforces that it is set exactly when status is DONE. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Verdict verdict;

    @Column(name = "runtime_ms")
    private Integer runtimeMs;

    /**
     * How the verdict was reached. Null for rows that predate the column - the seeded history and
     * anything judged before it existed - and left null rather than backfilled, because a guess
     * here is indistinguishable from a fact for everyone reading it afterwards.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "judged_by", length = 20)
    private JudgingMethod judgedBy;

    @CreatedDate
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    public boolean isAccepted() {
        return verdict == Verdict.AC;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Submission other)) {
            return false;
        }
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Submission.class.hashCode();
    }
}
