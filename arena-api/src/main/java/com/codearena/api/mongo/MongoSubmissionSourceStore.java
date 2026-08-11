package com.codearena.api.mongo;

import com.codearena.api.service.SubmissionSourceStore;
import com.codearena.common.domain.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.util.Optional;

/**
 * Durable {@link SubmissionSourceStore} backed by MongoDB.
 *
 * <h2>Why a failed write does not fail the submission</h2>
 *
 * <p>Storing the source is an archival step, not part of judging: the source travels to the
 * judge inside the Kafka event, so a submission whose archive write failed is still judged and
 * still scored correctly. Propagating the failure would roll back the submission and hand the
 * user a 500 for a problem that has nothing to do with their code - letting a document store
 * decide whether the platform accepts work.
 *
 * <p>What is lost is the ability to read that source back later, which the endpoint already
 * reports as a 404. So the failure is logged loudly and swallowed, and the cost is one missing
 * archive rather than one rejected submission.
 */
public class MongoSubmissionSourceStore implements SubmissionSourceStore {

    private static final Logger log = LoggerFactory.getLogger(MongoSubmissionSourceStore.class);

    private final SubmissionSourceRepository repository;
    private final Clock clock;

    public MongoSubmissionSourceStore(SubmissionSourceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void store(Long submissionId, Language language, String sourceCode) {
        try {
            repository.save(new SubmissionSource(submissionId, language, sourceCode, clock.instant()));
        } catch (DataAccessException e) {
            log.warn("Could not archive source for submission {}: {}. The submission is judged "
                    + "regardless; only later retrieval of its source is lost.",
                    submissionId, e.getMessage());
        }
    }

    @Override
    public Optional<String> find(Long submissionId) {
        try {
            return repository.findById(submissionId).map(SubmissionSource::getSourceCode);
        } catch (DataAccessException e) {
            log.warn("Could not read source for submission {}: {}", submissionId, e.getMessage());
            return Optional.empty();
        }
    }
}
