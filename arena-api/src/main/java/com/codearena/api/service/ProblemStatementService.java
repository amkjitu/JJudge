package com.codearena.api.service;

import com.codearena.api.mongo.ProblemStatement;
import com.codearena.api.mongo.ProblemStatementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reads problem prose from MongoDB, and copes with there being none.
 *
 * <p>A missing statement is not an error. The relational record is what makes a problem real -
 * it has a rating, tags and submissions - and the prose is an enrichment on top. Whether Mongo
 * is unavailable, or simply has nothing for this slug yet, the honest answer to the detail page
 * is the same: no statement. It renders its "no statement available" branch and the rest of the
 * page works.
 *
 * <p>Taking an {@link ObjectProvider} rather than the repository directly is what lets the
 * application run with no document store at all - the same fallback the source store makes,
 * for the same reason.
 */
@Service
public class ProblemStatementService {

    private static final Logger log = LoggerFactory.getLogger(ProblemStatementService.class);

    private final ObjectProvider<ProblemStatementRepository> repository;

    public ProblemStatementService(ObjectProvider<ProblemStatementRepository> repository) {
        this.repository = repository;
    }

    public Optional<ProblemStatement> findBySlug(String slug) {
        ProblemStatementRepository statements = repository.getIfAvailable();
        if (statements == null) {
            return Optional.empty();
        }
        try {
            return statements.findById(slug);
        } catch (DataAccessException e) {
            log.warn("Could not read the statement for '{}': {}", slug, e.getMessage());
            return Optional.empty();
        }
    }

    /** Just the Markdown body, which is all the API and the detail page need. */
    public Optional<String> markdownFor(String slug) {
        return findBySlug(slug).map(ProblemStatement::getStatementMarkdown);
    }
}
