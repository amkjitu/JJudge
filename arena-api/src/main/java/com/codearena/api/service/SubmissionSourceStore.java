package com.codearena.api.service;

import com.codearena.common.domain.Language;

import java.util.Optional;

/**
 * Storage port for submitted source code.
 *
 * <p>Source code is deliberately kept out of PostgreSQL: it is an opaque blob that is never
 * queried, filtered or joined on, and putting 64 KiB of text in a row that the leaderboard and
 * recommender scan constantly would bloat every one of those reads.
 *
 * <p>Backed by MongoDB in a normal deployment, and by {@link InMemorySubmissionSourceStore}
 * when none is configured. The port exists so that choice is a wiring detail rather than
 * something {@code SubmissionService} has to know about.
 *
 * @see com.codearena.api.config.SubmissionSourceStoreConfig
 */
public interface SubmissionSourceStore {

    void store(Long submissionId, Language language, String sourceCode);

    Optional<String> find(Long submissionId);
}
