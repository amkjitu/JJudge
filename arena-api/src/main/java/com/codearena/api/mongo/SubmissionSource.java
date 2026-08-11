package com.codearena.api.mongo;

import com.codearena.common.domain.Language;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * A submission's source code, stored outside PostgreSQL.
 *
 * <p>Source is an opaque blob: never queried, never filtered, never joined on. Keeping it in
 * the {@code submissions} row would put tens of kilobytes of text in a table the leaderboard,
 * the profile charts and the recommender all scan, and PostgreSQL would either inline it into
 * every one of those reads or push it to TOAST storage and read it back through an extra
 * indirection. A document store is the honest shape for it.
 *
 * <p>The PostgreSQL submission id <em>is</em> the {@code _id}. That is deliberate: it gives the
 * lookup a primary-key index for free, and it makes writes idempotent, so a retried store after
 * a network blip overwrites rather than accumulating a second copy of the same submission.
 */
@Document(collection = SubmissionSource.COLLECTION)
public class SubmissionSource {

    public static final String COLLECTION = "submission_sources";

    @Id
    private Long submissionId;

    @Field("language")
    private Language language;

    @Field("source_code")
    private String sourceCode;

    @Field("stored_at")
    private Instant storedAt;

    protected SubmissionSource() {
        // Spring Data materialises documents reflectively.
    }

    public SubmissionSource(Long submissionId, Language language, String sourceCode, Instant storedAt) {
        this.submissionId = submissionId;
        this.language = language;
        this.sourceCode = sourceCode;
        this.storedAt = storedAt;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public Language getLanguage() {
        return language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public Instant getStoredAt() {
        return storedAt;
    }
}
