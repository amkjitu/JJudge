package com.codearena.ai.hint;

import com.codearena.ai.AnswerSource;
import com.codearena.ai.config.AiProperties;
import com.codearena.ai.config.ModelAvailability;
import com.codearena.ai.web.dto.HintRequest;
import com.codearena.ai.web.dto.HintResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hints with no model available - which is the path most people running this project will take,
 * and therefore the one worth testing hardest.
 */
@DisplayName("HintService without a model")
class HintServiceTest {

    private final AiProperties properties = new AiProperties(true, Duration.ofSeconds(5), 20_000);

    private final HintService service = new HintService(
            noChatClient(), new TagHintLibrary(), properties,
            new ModelAvailability(java.time.Clock.systemUTC()));

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatClient> noChatClient() {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static HintRequest request(int level, String... tags) {
        return new HintRequest("Maximum Subarray Sum", Set.of(tags), 1100, level, null);
    }

    @Nested
    @DisplayName("labelling")
    class Labelling {

        @Test
        @DisplayName("says the answer is heuristic rather than implying a model spoke")
        void labelsTheSource() {
            // The whole point of the enum. A library hint presented as an AI hint spends
            // credibility the service has not earned.
            assertThat(service.hint(request(1, "dp")).source()).isEqualTo(AnswerSource.HEURISTIC);
        }
    }

    @Nested
    @DisplayName("levels")
    class Levels {

        @Test
        @DisplayName("a higher level gives a different, more specific hint")
        void levelsDiffer() {
            String first = service.hint(request(1, "dp")).hint();
            String second = service.hint(request(2, "dp")).hint();

            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("a level past the maximum is clamped rather than rejected")
        void clampsAboveMaximum() {
            HintResponse response = service.hint(request(99, "dp"));

            assertThat(response.level()).isEqualTo(HintService.MAX_LEVEL);
            assertThat(response.hint()).isNotBlank();
        }

        @Test
        @DisplayName("a missing level defaults to the gentlest hint")
        void defaultsToLevelOne() {
            HintRequest noLevel = new HintRequest("Some Problem", Set.of("dp"), 1100, null, null);

            assertThat(service.hint(noLevel).level()).isEqualTo(1);
        }

        @Test
        @DisplayName("a technique with fewer hints than levels returns its most specific one")
        void shortLibraryEntry() {
            // two-pointers has two hints, not three. Asking for level 3 must not index past the
            // end - it should give the most specific hint available.
            HintResponse response = service.hint(request(3, "two-pointers"));

            assertThat(response.hint()).isNotBlank();
            assertThat(response.level()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("tag selection")
    class TagSelection {

        @Test
        @DisplayName("hints are chosen for the problem's technique")
        void picksByTag() {
            assertThat(service.hint(request(1, "binary-search")).hint())
                    .containsIgnoringCase("predicate");
            assertThat(service.hint(request(1, "sliding-window")).hint())
                    .containsIgnoringCase("window");
        }

        @Test
        @DisplayName("the more specific technique wins when a problem has several tags")
        void prefersTheMoreSpecificTag() {
            // longest-increasing-subsequence carries both dp and binary-search. Hinting it as dp
            // points at the actual difficulty; the binary search is an optimisation of that.
            String hint = service.hint(request(1, "dp", "binary-search")).hint();

            assertThat(hint).isEqualTo(service.hint(request(1, "dp")).hint());
        }

        @Test
        @DisplayName("an unknown tag falls back to generic advice rather than nothing")
        void unknownTag() {
            assertThat(service.hint(request(1, "quantum-teleportation")).hint()).isNotBlank();
        }

        @Test
        @DisplayName("no tags at all still produces a hint")
        void noTags() {
            HintRequest bare = new HintRequest("Some Problem", null, 1000, 1, null);

            assertThat(service.hint(bare).hint()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("content")
    class Content {

        @Test
        @DisplayName("no library hint contains code")
        void hintsAreNotSolutions() {
            // A hint that hands over an implementation is not a hint. These are fixed strings, so
            // this is enforceable rather than aspirational.
            TagHintLibrary library = new TagHintLibrary();
            for (String tag : Set.of("dp", "graph", "binary-search", "greedy", "heap", "hashing",
                    "sliding-window", "two-pointers", "stack", "sorting", "strings", "math",
                    "prefix-sum", "shortest-path")) {
                assertThat(library.knows(tag)).as("tag %s should be known", tag).isTrue();

                for (String hint : library.forTags(Set.of(tag))) {
                    assertThat(hint)
                            .as("hint for %s should not contain code", tag)
                            .doesNotContain("for (")
                            .doesNotContain("while (")
                            .doesNotContain("{")
                            .doesNotContain("return ");
                }
            }
        }
    }
}
