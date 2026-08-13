package com.codearena.api.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads the bundled problem statements into MongoDB.
 *
 * <h2>Why this is not Flyway</h2>
 *
 * <p>MongoDB has no schema to migrate, so the relational answer - versioned DDL, applied once,
 * recorded in a table - has nothing to attach to. What is left is seed content, and the useful
 * property is not "run exactly once" but "end up in a known state".
 *
 * <p>So this upserts by slug on every start. Re-running is harmless, editing a statement in the
 * JSON and restarting picks the change up, and a half-finished first run repairs itself next
 * time. A run-once guard would give the opposite behaviour on all three counts.
 *
 * <p>It deliberately does not delete statements it does not recognise: an operator who wrote a
 * statement through some other route should not lose it to a redeploy.
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than during startup so that a MongoDB which is
 * slow or missing delays no part of the application coming up. Seeding is best-effort; every
 * consumer of this collection already treats a missing statement as a normal state.
 */
@Component
public class ProblemStatementSeeder {

    private static final Logger log = LoggerFactory.getLogger(ProblemStatementSeeder.class);

    /** Used only in the "already populated" log line. */
    private static final String WHAT = "problem statement";

    private static final String RESOURCE = "mongo/problem-statements.json";

    private final ProblemStatementRepository repository;
    private final ObjectMapper objectMapper;

    public ProblemStatementSeeder(ProblemStatementRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        // First run only. These documents are editable from the admin UI, and re-seeding on every
        // startup would silently revert an editor's work on the next deploy - a data-loss bug that
        // looks like the save button not working. The bundled JSON is the starting catalogue, not
        // the source of truth for a database that has since been used.
        //
        // The check is "is the collection empty" rather than a per-document merge because a
        // half-seeded collection is not a state this can reason about: a problem deleted on
        // purpose would reappear, which is the same bug wearing a different hat.
        long existing = repository.count();
        if (existing > 0) {
            log.info("MongoDB already holds {} {} documents; leaving them alone",
                    existing, WHAT);
            return;
        }

        List<StatementDocument> statements;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            statements = List.of(objectMapper.readValue(in, StatementDocument[].class));
        } catch (IOException e) {
            // A malformed bundled resource is a build problem, not a runtime condition, so it is
            // worth an ERROR - but still not worth refusing to serve problems over.
            log.error("Could not read {}: {}", RESOURCE, e.getMessage());
            return;
        }

        try {
            repository.saveAll(statements.stream().map(StatementDocument::toDocument).toList());
            log.info("Seeded {} problem statements into MongoDB", statements.size());
        } catch (DataAccessException e) {
            log.warn("Could not seed problem statements: {}. Problems will render without prose.",
                    e.getMessage());
        }
    }

    /**
     * The JSON shape, kept separate from the document so the file can stay readable - camelCase
     * keys, no {@code _id} - while the stored document keeps snake_case fields and uses the slug
     * as its identifier.
     */
    record StatementDocument(String slug,
                             String statementMarkdown,
                             String editorialMarkdown,
                             List<Example> examples) {

        ProblemStatement toDocument() {
            List<ProblemStatement.WorkedExample> worked = examples == null ? List.of()
                    : examples.stream()
                    .map(e -> new ProblemStatement.WorkedExample(e.input(), e.output(), e.explanation()))
                    .toList();
            return new ProblemStatement(slug, statementMarkdown, editorialMarkdown, worked);
        }

        record Example(String input, String output, String explanation) {
        }
    }
}
