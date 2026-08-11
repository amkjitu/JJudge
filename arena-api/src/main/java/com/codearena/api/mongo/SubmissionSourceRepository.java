package com.codearena.api.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubmissionSourceRepository extends MongoRepository<SubmissionSource, Long> {
}
