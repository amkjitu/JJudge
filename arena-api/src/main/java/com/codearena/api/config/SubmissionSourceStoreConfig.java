package com.codearena.api.config;

import com.codearena.api.mongo.MongoSubmissionSourceStore;
import com.codearena.api.mongo.SubmissionSourceRepository;
import com.codearena.api.service.InMemorySubmissionSourceStore;
import com.codearena.api.service.SubmissionSourceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Chooses where submitted source code is kept.
 *
 * <p>MongoDB when it is configured, the bounded in-process map otherwise. The fallback is not
 * decoration: it keeps {@code ./mvnw test} and the controller slices runnable without a document
 * store, and it means a developer who has not started Mongo gets a working application with one
 * clearly-logged limitation rather than a context that refuses to load.
 *
 * <p>Resolved through {@link ObjectProvider} inside a single bean method rather than with two
 * beans and {@code @ConditionalOnBean}, for the same reason as
 * {@link com.codearena.api.ratelimit.RateLimitConfig}: that annotation only evaluates reliably
 * in auto-configuration, where Spring can guarantee ordering. In a user {@code @Configuration}
 * the answer depends on which bean definition happens to be registered first.
 *
 * <p>Being a plain {@code @Configuration} also keeps both implementations out of
 * {@code @WebMvcTest} slices, which do not scan configuration classes and have no business
 * owning a storage decision.
 */
@Configuration
public class SubmissionSourceStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(SubmissionSourceStoreConfig.class);

    @Bean
    public SubmissionSourceStore submissionSourceStore(
            ObjectProvider<SubmissionSourceRepository> sourceRepository, Clock clock) {

        SubmissionSourceRepository repository = sourceRepository.getIfAvailable();
        if (repository != null) {
            log.info("Submission source code is stored in MongoDB and survives a restart.");
            return new MongoSubmissionSourceStore(repository, clock);
        }

        return new InMemorySubmissionSourceStore();
    }
}
