package com.codearena.api.mongo;

import com.codearena.api.service.SubmissionSourceStore;
import com.codearena.api.support.MongoTestContainer;
import com.codearena.api.support.PostgresTestContainer;
import com.codearena.api.support.RedisTestContainer;
import com.codearena.common.domain.Language;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Source storage against a real MongoDB.
 *
 * <p>Also asserts the wiring: that the port resolves to the Mongo implementation when a document
 * store is present. Getting that backwards is silent - the in-memory store answers every read
 * correctly within a single process, so a test that only exercised store-then-find would pass
 * against the fallback and prove nothing about durability.
 */
@SpringBootTest
@DisplayName("MongoDB submission source storage")
class MongoSubmissionSourceStoreIT {

    private static final long TEST_SUBMISSION_ID = 900_001L;

    @Autowired
    private SubmissionSourceStore store;

    @Autowired
    private SubmissionSourceRepository repository;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestContainer.registerProperties(registry);
        RedisTestContainer.registerProperties(registry);
        MongoTestContainer.registerProperties(registry);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("arena.jwt.secret", () -> "test-only-signing-key-0123456789abcdefghijklmnop");
        registry.add("arena.jwt.issuer", () -> "codearena-test");
    }

    @AfterEach
    void removeTestDocuments() {
        repository.deleteById(TEST_SUBMISSION_ID);
    }

    @Test
    @DisplayName("resolves to the MongoDB store rather than the in-memory fallback")
    void mongoStoreIsWiredIn() {
        assertThat(store).isInstanceOf(MongoSubmissionSourceStore.class);
    }

    @Test
    @DisplayName("stores source and reads it back verbatim")
    void roundTrip() {
        String source = "public class Main {\n    // tabs\tand \"quotes\" and é\n}\n";

        store.store(TEST_SUBMISSION_ID, Language.JAVA, source);

        assertThat(store.find(TEST_SUBMISSION_ID)).hasValue(source);
    }

    @Test
    @DisplayName("keeps the language and the storage time alongside the source")
    void storesMetadata() {
        store.store(TEST_SUBMISSION_ID, Language.PYTHON, "print(1)");

        SubmissionSource stored = repository.findById(TEST_SUBMISSION_ID).orElseThrow();
        assertThat(stored.getLanguage()).isEqualTo(Language.PYTHON);
        assertThat(stored.getStoredAt()).isNotNull();
    }

    @Test
    @DisplayName("storing twice overwrites rather than accumulating duplicates")
    void storeIsIdempotent() {
        // The PostgreSQL submission id is the _id, so a retry after a network blip replaces the
        // document instead of leaving two copies with no way to tell which is current.
        store.store(TEST_SUBMISSION_ID, Language.CPP, "int main(){}");
        store.store(TEST_SUBMISSION_ID, Language.CPP, "int main(){ return 0; }");

        assertThat(store.find(TEST_SUBMISSION_ID)).hasValue("int main(){ return 0; }");
        // Scoped to this id rather than a collection-wide count: the Mongo container is shared
        // across the run, so other tests' submissions live here too.
        assertThat(repository.findAllById(List.of(TEST_SUBMISSION_ID))).hasSize(1);
    }

    @Test
    @DisplayName("an unknown submission has no source rather than an empty string")
    void unknownSubmission() {
        assertThat(store.find(404_404L)).isEmpty();
    }
}
