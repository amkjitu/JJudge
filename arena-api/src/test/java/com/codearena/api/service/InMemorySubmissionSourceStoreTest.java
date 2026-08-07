package com.codearena.api.service;

import com.codearena.common.domain.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemorySubmissionSourceStore")
class InMemorySubmissionSourceStoreTest {

    private final InMemorySubmissionSourceStore store = new InMemorySubmissionSourceStore();

    @Test
    @DisplayName("round-trips stored source")
    void roundTrip() {
        store.store(1L, Language.JAVA, "class Main {}");

        assertThat(store.find(1L)).contains("class Main {}");
    }

    @Test
    @DisplayName("returns empty for an unknown submission rather than null")
    void unknownSubmission() {
        assertThat(store.find(999L)).isEmpty();
    }

    @Test
    @DisplayName("evicts the least recently used entry once the cap is exceeded")
    void evictsBeyondCap() {
        for (long id = 1; id <= 500; id++) {
            store.store(id, Language.JAVA, "solution " + id);
        }
        // Touch the oldest entry so it is no longer the least recently *used*.
        assertThat(store.find(1L)).isPresent();

        store.store(501L, Language.JAVA, "solution 501");

        assertThat(store.find(501L)).isPresent();
        assertThat(store.find(1L)).as("recently accessed entry survives").isPresent();
        assertThat(store.find(2L)).as("genuinely least-recently-used entry is evicted").isEmpty();
    }

    @Test
    @DisplayName("overwrites source when the same submission is stored twice")
    void overwrite() {
        store.store(1L, Language.JAVA, "first");
        store.store(1L, Language.PYTHON, "second");

        assertThat(store.find(1L)).contains("second");
    }
}
