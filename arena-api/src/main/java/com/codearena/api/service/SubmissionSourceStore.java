package com.codearena.api.service;

import com.codearena.common.domain.Language;

import java.util.Optional;

/**
 * Storage port for submitted source code.
 *
 * <p>Source code is deliberately kept out of PostgreSQL: it is an opaque blob that is never
 * queried, filtered or joined on, and putting 64 KiB of text in a row that the leaderboard and
 * recommender scan constantly would bloat every one of those reads. Phase 7 backs this with
 * MongoDB; until then {@link InMemorySubmissionSourceStore} keeps it in process.
 */
public interface SubmissionSourceStore {

    void store(Long submissionId, Language language, String sourceCode);

    Optional<String> find(Long submissionId);
}
