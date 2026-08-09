package com.codearena.api.recommendation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which topics a user is ready for, given the prerequisite DAG.
 *
 * <h2>Why a topological sort</h2>
 *
 * <p>Readiness is transitive: {@code segment-tree} depends on {@code tree}, which depends on
 * {@code recursion}. Answering "is this topic ready?" independently per topic would mean
 * walking the ancestor chain each time - O(V·E) across the taxonomy, and awkward to make
 * terminate on a malformed graph.
 *
 * <p>Processing topics in topological order instead means every prerequisite is already decided
 * when a topic is reached, so the whole readiness map falls out of a single O(V + E) pass with
 * no recursion and no repeated work. That is the entire justification for the sort, and it is
 * why the DAG is stored as edges rather than as a denormalised "ready topics" column.
 *
 * <h2>What counts as blocked</h2>
 *
 * <p>A topic is blocked when a prerequisite has been <em>demonstrably not learned</em>, or when a
 * prerequisite is itself blocked. Demonstrably means two things at once: a raw success rate below
 * the floor, <em>and</em> enough attempts for that rate to carry information. Absence of evidence
 * is not evidence of absence, and treating it as failure would leave a brand-new account with
 * nothing to solve - the gate would be at its most aggressive exactly when it knows least.
 *
 * <p>The evidence requirement is not decoration, and getting it wrong is not a subtle
 * mis-ranking. Judging on the <em>smoothed</em> proficiency scores "attempted once, solved it" at
 * 0.25 - below any sensible floor - so a perfect record reads as a failure. On a root topic like
 * {@code implementation} that verdict propagates to every descendant, which is nearly the entire
 * taxonomy. See {@link TagProficiency} for why ranking and gating need different measures.
 *
 * <p>The practical effect: the gate is inert for newcomers and sharp for users with real history
 * showing a gap. Someone who has failed most of the {@code arrays} problems they have tried stops
 * being offered {@code segment-tree}, because {@code arrays} is blocked and blockage propagates.
 */
public final class PrerequisiteGate {

    private final Set<String> blockedTags;

    private PrerequisiteGate(Set<String> blockedTags) {
        this.blockedTags = blockedTags;
    }

    /**
     * @param prerequisites        topic to the topics that should come first; topics absent from
     *                             the map, or mapped to an empty set, are roots
     * @param proficiency          the user's per-topic record
     * @param masteryFloor         raw success rate at or above which a topic counts as learned
     * @param minEvidenceAttempts  attempts needed before a topic may be judged at all
     */
    public static PrerequisiteGate build(Map<String, Set<String>> prerequisites,
                                         TagProficiency proficiency,
                                         double masteryFloor,
                                         int minEvidenceAttempts) {
        List<String> order = topologicalOrder(prerequisites);
        Set<String> blocked = new HashSet<>();

        // Single pass in dependency order: when a topic is reached, every prerequisite has
        // already been classified, so no topic is ever revisited.
        for (String tag : order) {
            for (String prerequisite : prerequisites.getOrDefault(tag, Set.of())) {
                boolean prerequisiteBlocked = blocked.contains(prerequisite);
                boolean triedAndNotLearned = proficiency.isDemonstrablyWeak(
                        prerequisite, masteryFloor, minEvidenceAttempts);

                if (prerequisiteBlocked || triedAndNotLearned) {
                    blocked.add(tag);
                    break;
                }
            }
        }

        return new PrerequisiteGate(Set.copyOf(blocked));
    }

    /** A gate that blocks nothing, for callers that want scoring without prerequisite logic. */
    public static PrerequisiteGate open() {
        return new PrerequisiteGate(Set.of());
    }

    public boolean isBlocked(String tag) {
        return blockedTags.contains(tag);
    }

    /**
     * A problem is blocked when <em>every</em> topic it covers is blocked.
     *
     * <p>Not "any": most problems carry an easy tag alongside a hard one, so requiring all tags
     * to be clear would reject nearly everything. If a problem offers at least one topic the
     * user is ready for, it is a legitimate way in.
     */
    public boolean blocksProblem(Set<String> tags) {
        if (tags.isEmpty()) {
            return false;
        }
        for (String tag : tags) {
            if (!isBlocked(tag)) {
                return false;
            }
        }
        return true;
    }

    public Set<String> blockedTags() {
        return blockedTags;
    }

    /**
     * Kahn's algorithm over prerequisite -> dependent edges, O(V + E).
     *
     * <p>If the graph somehow contains a cycle, the nodes on it never reach in-degree zero and
     * are appended at the end rather than dropped. The seeded taxonomy is acyclic and a test
     * asserts it, but a recommendation engine silently losing topics because someone added a
     * bad edge is a worse failure than one that degrades to unordered.
     */
    static List<String> topologicalOrder(Map<String, Set<String>> prerequisites) {
        Set<String> nodes = new LinkedHashSet<>(prerequisites.keySet());
        prerequisites.values().forEach(nodes::addAll);

        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        nodes.forEach(node -> inDegree.put(node, 0));

        for (Map.Entry<String, Set<String>> entry : prerequisites.entrySet()) {
            for (String prerequisite : entry.getValue()) {
                dependents.computeIfAbsent(prerequisite, k -> new ArrayList<>()).add(entry.getKey());
                inDegree.merge(entry.getKey(), 1, Integer::sum);
            }
        }

        Deque<String> ready = new ArrayDeque<>();
        inDegree.forEach((node, degree) -> {
            if (degree == 0) {
                ready.add(node);
            }
        });

        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String current = ready.poll();
            order.add(current);
            for (String dependent : dependents.getOrDefault(current, List.of())) {
                if (inDegree.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (order.size() < nodes.size()) {
            Set<String> onCycle = new LinkedHashSet<>(nodes);
            order.forEach(onCycle::remove);
            order.addAll(onCycle);
        }

        return Collections.unmodifiableList(order);
    }
}
