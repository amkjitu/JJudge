package com.codearena.api.recommendation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PrerequisiteGate")
class PrerequisiteGateTest {

    /** A slice of the real taxonomy: arrays <- implementation, and so on downstream. */
    private static final Map<String, Set<String>> DAG = Map.of(
            "arrays", Set.of("implementation"),
            "sorting", Set.of("arrays"),
            "binary-search", Set.of("sorting"),
            "tree", Set.of("recursion"),
            "segment-tree", Set.of("tree", "prefix-sum"),
            "prefix-sum", Set.of("arrays"),
            "recursion", Set.of("implementation"),
            "dp", Set.of("recursion")
    );

    /** Builds a record set from raw solved/attempted counts. */
    private static TagProficiency proficiency(Map<String, int[]> counts) {
        Map<String, TagProficiency.TagRecord> records = new java.util.HashMap<>();
        counts.forEach((tag, sa) -> records.put(tag, new TagProficiency.TagRecord(sa[0], sa[1])));
        return new TagProficiency(records, 3.0);
    }

    private static PrerequisiteGate gate(TagProficiency proficiency) {
        return PrerequisiteGate.build(DAG, proficiency, 0.34, 3);
    }

    @Nested
    @DisplayName("topological order")
    class Order {

        @Test
        @DisplayName("places every topic after all of its prerequisites")
        void respectsDependencies() {
            List<String> order = PrerequisiteGate.topologicalOrder(DAG);

            for (Map.Entry<String, Set<String>> entry : DAG.entrySet()) {
                for (String prerequisite : entry.getValue()) {
                    assertThat(order.indexOf(prerequisite))
                            .as("%s must come before %s", prerequisite, entry.getKey())
                            .isLessThan(order.indexOf(entry.getKey()));
                }
            }
        }

        @Test
        @DisplayName("includes topics that appear only as prerequisites")
        void includesRootsNotPresentAsKeys() {
            List<String> order = PrerequisiteGate.topologicalOrder(DAG);

            // 'implementation' is never a key, only a value - a naive implementation over
            // keySet() alone would silently drop it and every ordering guarantee with it.
            assertThat(order).contains("implementation");
            assertThat(order).hasSize(9);
        }

        @Test
        @DisplayName("keeps cyclic nodes rather than dropping them")
        void cycleDegradesGracefully() {
            Map<String, Set<String>> cyclic = Map.of(
                    "a", Set.of("b"),
                    "b", Set.of("a"),
                    "c", Set.of());

            List<String> order = PrerequisiteGate.topologicalOrder(cyclic);

            // No valid order exists for a and b, but losing topics entirely would silently
            // shrink the taxonomy - a far worse failure than an arbitrary position.
            assertThat(order).containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("handles an empty graph")
        void emptyGraph() {
            assertThat(PrerequisiteGate.topologicalOrder(Map.of())).isEmpty();
        }
    }

    @Nested
    @DisplayName("blocking")
    class Blocking {

        @Test
        @DisplayName("blocks nothing for a user with no history")
        void newUserIsNotGated() {
            PrerequisiteGate gate = gate(proficiency(Map.of()));

            // The gate is at its least informed here, so it must also be at its least
            // aggressive - otherwise a new account sees an empty recommendation panel.
            assertThat(gate.blockedTags()).isEmpty();
        }

        @Test
        @DisplayName("blocks a topic whose prerequisite was attempted and not learned")
        void weakPrerequisiteBlocks() {
            PrerequisiteGate gate = gate(proficiency(Map.of("arrays", new int[]{1, 10})));

            assertThat(gate.isBlocked("sorting")).isTrue();
        }

        @Test
        @DisplayName("does not block when the prerequisite is comfortably learned")
        void strongPrerequisiteDoesNotBlock() {
            PrerequisiteGate gate = gate(proficiency(Map.of("arrays", new int[]{8, 10})));

            assertThat(gate.isBlocked("sorting")).isFalse();
        }

        @Test
        @DisplayName("propagates downstream through the whole chain")
        void blockingIsTransitive() {
            // arrays is weak -> sorting blocked -> binary-search blocked, and separately
            // prefix-sum blocked -> segment-tree blocked. This transitivity is exactly what
            // the topological order buys.
            PrerequisiteGate gate = gate(proficiency(Map.of("arrays", new int[]{0, 10})));

            assertThat(gate.isBlocked("sorting")).isTrue();
            assertThat(gate.isBlocked("binary-search")).isTrue();
            assertThat(gate.isBlocked("prefix-sum")).isTrue();
            assertThat(gate.isBlocked("segment-tree")).isTrue();
            // recursion descends from implementation, which is untouched, so it stays open
            assertThat(gate.isBlocked("recursion")).isFalse();
            assertThat(gate.isBlocked("dp")).isFalse();
        }

        @Test
        @DisplayName("the mastery floor is the boundary, inclusive")
        void masteryFloorIsInclusive() {
            assertThat(gate(proficiency(Map.of("arrays", new int[]{34, 100}))).isBlocked("sorting")).isFalse();
            assertThat(gate(proficiency(Map.of("arrays", new int[]{33, 100}))).isBlocked("sorting")).isTrue();
        }

        @Test
        @DisplayName("a perfect record on one attempt does not count as failure")
        void thinButPerfectEvidenceDoesNotBlock() {
            // Regression guard for the bug that made the engine return 2 suggestions instead of
            // 10. Gating on the *smoothed* proficiency scores 1-solved-of-1-attempted at
            // 1/(1+3) = 0.25, below the 0.34 floor - so a flawless record read as a failure.
            // On the root topic 'implementation' that verdict cascaded to every descendant and
            // gated out most of the catalogue.
            PrerequisiteGate gate = gate(proficiency(Map.of("implementation", new int[]{1, 1})));

            assertThat(gate.blockedTags())
                    .as("one solved out of one attempted is not evidence of failure")
                    .isEmpty();
        }

        @Test
        @DisplayName("judges only once there are enough attempts to judge on")
        void requiresMinimumEvidence() {
            // Two attempts, both failed: a bad ratio on a sample too small to mean anything.
            assertThat(gate(proficiency(Map.of("arrays", new int[]{0, 2}))).isBlocked("sorting"))
                    .isFalse();

            // The third attempt crosses the evidence threshold and the same ratio now counts.
            assertThat(gate(proficiency(Map.of("arrays", new int[]{0, 3}))).isBlocked("sorting"))
                    .isTrue();
        }

        @Test
        @DisplayName("uses the raw ratio, not the smoothed one, once evidence is sufficient")
        void gatesOnRawRatio() {
            // 4 of 10 is comfortably above the 0.34 floor on the raw ratio, but only
            // 4/(10+3) = 0.31 smoothed. Gating on the smoothed value would block a topic the
            // user solves 40% of the time.
            assertThat(gate(proficiency(Map.of("arrays", new int[]{4, 10}))).isBlocked("sorting"))
                    .isFalse();
        }

        @Test
        @DisplayName("a root topic is never blocked")
        void rootsAreAlwaysOpen() {
            PrerequisiteGate gate = gate(proficiency(Map.of("implementation", new int[]{0, 5})));

            assertThat(gate.isBlocked("implementation")).isFalse();
            // ... but everything downstream of it now is
            assertThat(gate.isBlocked("arrays")).isTrue();
            assertThat(gate.isBlocked("recursion")).isTrue();
        }
    }

    @Nested
    @DisplayName("problem-level gating")
    class Problems {

        @Test
        @DisplayName("a problem is blocked only when every one of its topics is")
        void oneOpenTagIsEnough() {
            PrerequisiteGate gate = gate(proficiency(Map.of("arrays", new int[]{0, 10})));

            // sorting is blocked, dp is not - so a problem covering both is still a legitimate
            // way in. Requiring every tag to be clear would reject almost the whole catalogue.
            assertThat(gate.blocksProblem(Set.of("sorting", "dp"))).isFalse();
            assertThat(gate.blocksProblem(Set.of("sorting", "binary-search"))).isTrue();
        }

        @Test
        @DisplayName("an untagged problem is never blocked")
        void untaggedProblemPasses() {
            PrerequisiteGate gate = gate(proficiency(Map.of("arrays", new int[]{0, 10})));

            assertThat(gate.blocksProblem(Set.of())).isFalse();
        }

        @Test
        @DisplayName("an open gate blocks nothing at all")
        void openGate() {
            assertThat(PrerequisiteGate.open().blocksProblem(Set.of("anything"))).isFalse();
            assertThat(PrerequisiteGate.open().blockedTags()).isEmpty();
        }
    }
}
