package com.codearena.common.domain;

/**
 * Lifecycle of a submission as it moves through the async judging pipeline:
 * {@code QUEUED} once published to Kafka, {@code RUNNING} while the judge worker evaluates it,
 * {@code DONE} once a verdict has been written back.
 */
public enum SubmissionStatus {
    QUEUED,
    RUNNING,
    DONE
}
