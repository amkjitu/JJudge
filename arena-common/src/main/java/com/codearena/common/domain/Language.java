package com.codearena.common.domain;

import java.util.Arrays;
import java.util.List;

/**
 * Languages a solution may be submitted in.
 *
 * <p>Not every value here can be judged. The runner image carries a toolchain for some of them
 * and not others, and the difference matters at the point of submission: a language the judge
 * cannot run produces no verdict at all, so the submission sits at {@code QUEUED} for ever while
 * its author waits for an answer that is never coming.
 *
 * <p>So the fact is recorded here, in the one module both the API and the judge depend on,
 * rather than being implied by a lookup table inside the judge that nothing upstream can see.
 * The API refuses submissions in a language that cannot be judged, and the judge's toolchain
 * table is tested against this list — adding a toolchain without flipping the flag, or the other
 * way round, fails the build instead of reaching a user.
 *
 * <p>The unexecutable values stay in the enum because the seeded submission history contains
 * them, and because "we cannot run this yet" is a truer thing to record than removing the
 * language as though nobody ever wrote one.
 */
public enum Language {
    JAVA(true),
    PYTHON(true),
    CPP(true),
    GO(false),
    JAVASCRIPT(false);

    private final boolean executable;

    Language(boolean executable) {
        this.executable = executable;
    }

    /** Whether the runner image can actually compile and run this. */
    public boolean isExecutable() {
        return executable;
    }

    /** The languages a submission may be made in, in declaration order. */
    public static List<Language> executable() {
        return Arrays.stream(values()).filter(Language::isExecutable).toList();
    }
}
