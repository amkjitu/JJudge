package com.codearena.judge.real;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProblemTestCaseRepository extends MongoRepository<ProblemTestCases, String> {
}
