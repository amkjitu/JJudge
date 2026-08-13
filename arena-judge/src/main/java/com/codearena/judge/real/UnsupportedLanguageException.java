package com.codearena.judge.real;

import com.codearena.common.domain.Language;

/**
 * The runner image has no toolchain for this language.
 *
 * <p>Deliberately not a verdict. Reporting CE would tell someone their correct code does not
 * compile; reporting RTE would tell them it crashed. Both are false, and both send them looking
 * for a bug they did not write. The listener treats this as the judge being unable to do its
 * job, which is what it is.
 */
public class UnsupportedLanguageException extends RuntimeException {

    private final transient Language language;

    public UnsupportedLanguageException(Language language) {
        super("This judge has no toolchain for " + language
                + "; the runner image carries Python, C++ and Java");
        this.language = language;
    }

    public Language language() {
        return language;
    }
}
