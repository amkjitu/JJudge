package com.codearena.api.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A hint as arena-api hands it on.
 *
 * <p>Deliberately a separate type from arena-ai's own response record rather than a shared class
 * in arena-common. The two services are deployed independently, and a shared DTO makes every
 * field change in one a compile-time break in the other - which is the coupling that makes
 * "independently deployable" untrue in practice. The event contracts in arena-common are shared
 * because a producer and a consumer genuinely must agree; an HTTP response only has to be
 * readable.
 *
 * @param source MODEL or HEURISTIC, passed through unchanged. The UI shows it, because a reader
 *               deserves to know whether a model reasoned about their problem or a fixed library
 *               matched a tag.
 */
@Schema(name = "Hint", description = "A nudge towards solving a problem")
public record HintView(String hint, int level, int maxLevel, String source) {
}
