package com.codearena.api.service;

import com.codearena.common.domain.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Non-durable {@link SubmissionSourceStore} for running without MongoDB.
 *
 * <p>Bounded LRU rather than an unbounded map: an in-memory store fed by a public endpoint is
 * a memory leak waiting to happen, and the cap makes the eviction behaviour explicit instead
 * of leaving it to the heap. Contents are lost on restart, which is stated plainly at startup
 * rather than discovered later.
 *
 * <p>Not a {@code @Component}: which store is in use is a decision, and it is made in one place
 * by {@link com.codearena.api.config.SubmissionSourceStoreConfig} rather than by two classes
 * both claiming the same interface and leaving Spring to arbitrate.
 */
public class InMemorySubmissionSourceStore implements SubmissionSourceStore {

    private static final Logger log = LoggerFactory.getLogger(InMemorySubmissionSourceStore.class);

    private static final int MAX_ENTRIES = 500;

    private final Map<Long, String> sources = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public InMemorySubmissionSourceStore() {
        log.warn("No MongoDB configured: submission source code is held in memory (last {} "
                + "submissions) and will not survive a restart. Set ARENA_MONGO_URI to store it "
                + "durably.", MAX_ENTRIES);
    }

    @Override
    public void store(Long submissionId, Language language, String sourceCode) {
        sources.put(submissionId, sourceCode);
    }

    @Override
    public Optional<String> find(Long submissionId) {
        return Optional.ofNullable(sources.get(submissionId));
    }
}
