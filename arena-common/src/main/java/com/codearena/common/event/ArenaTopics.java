package com.codearena.common.event;

/**
 * Topic names, shared so a producer and a consumer cannot disagree about them.
 *
 * <p>A typo in a topic name is a silent failure: the producer writes happily to a topic nobody
 * reads and the consumer waits forever on one nobody writes to. Nothing errors, nothing logs,
 * submissions simply stay QUEUED. A shared constant makes that particular mistake impossible.
 */
public final class ArenaTopics {

    /** Work queue: a submission has been accepted and needs judging. */
    public static final String SUBMISSIONS = "arena.submissions";

    /** Result stream: the judge has finished with a submission. */
    public static final String VERDICTS = "arena.verdicts";

    private ArenaTopics() {
    }
}
