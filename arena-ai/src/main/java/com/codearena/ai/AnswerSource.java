package com.codearena.ai;

/**
 * Who produced an answer.
 *
 * <p>Present on every response, and not optional. A heuristic estimate presented as if a model
 * had reasoned about the code is worse than no estimate at all: the reader calibrates their
 * trust on the label, and a wrong label spends credibility they did not agree to lend. The
 * fallback path is a feature of this service, so it is stated rather than disguised.
 */
public enum AnswerSource {

    /** A language model answered. */
    MODEL,

    /** No model was available or it did not answer in time; this came from static analysis. */
    HEURISTIC
}
