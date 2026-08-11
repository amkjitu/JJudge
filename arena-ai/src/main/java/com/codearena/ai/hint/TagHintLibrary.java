package com.codearena.ai.hint;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hints written per technique, used when no model is available.
 *
 * <h2>Why a library rather than generated text</h2>
 *
 * <p>A hint's job is to restart someone's thinking without handing over the answer, and that is
 * a property of the <em>technique</em>, not of the specific problem. "What subproblem would let
 * you extend a solution by one element?" is the right nudge for every dynamic-programming
 * problem on the platform. A fixed library gets that right by construction, whereas a model
 * asked for a hint will cheerfully give away the whole solution unless carefully restrained.
 *
 * <p>Hints are ordered from gentlest to most specific, so asking again reveals more. Nothing
 * here states an algorithm outright - the last hint names the technique, which is the most a
 * hint should do.
 */
@Component
public class TagHintLibrary {

    private static final List<String> GENERIC = List.of(
            "Re-read the constraints. The size of n usually rules out whole families of "
                    + "approaches and points at the intended one.",
            "Work the smallest interesting case by hand and write down what you did. The rule you "
                    + "followed is usually the algorithm.",
            "Ask what the brute force is and what makes it slow. The intended solution is most "
                    + "often that same idea with the wasted work removed.");

    private final Map<String, List<String>> byTag = byTag();

    /**
     * Hints for a problem carrying these tags, most specific technique first.
     *
     * @param tags the problem's tags; unknown ones are ignored
     */
    public List<String> forTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return GENERIC;
        }

        // Iteration order of the backing map is deliberate: the more specific technique wins when
        // a problem carries several, so a dp + binary-search problem is hinted as dp.
        for (Map.Entry<String, List<String>> entry : byTag.entrySet()) {
            if (tags.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return GENERIC;
    }

    public boolean knows(String tag) {
        return byTag.containsKey(tag);
    }

    private static Map<String, List<String>> byTag() {
        // LinkedHashMap: insertion order is the specificity order forTags relies on.
        Map<String, List<String>> hints = new LinkedHashMap<>();

        hints.put("dp", List.of(
                "Can you describe a state that captures everything you need to know about a "
                        + "prefix of the input, and nothing more?",
                "Write the answer for a prefix of length i in terms of shorter prefixes. The "
                        + "recurrence usually falls out of the last decision you made.",
                "Get the base cases right before the transition. Most wrong dynamic programs are "
                        + "correct recurrences with a wrong starting row."));

        hints.put("graph", List.of(
                "What are the vertices and what are the edges? Many problems are graph problems "
                        + "only after you decide what a node represents.",
                "Does the order you visit nodes in matter? That is usually the difference between "
                        + "a depth-first and a breadth-first traversal.",
                "Check whether you need to revisit nodes. If not, mark them the moment they are "
                        + "queued rather than when they are processed."));

        hints.put("shortest-path", List.of(
                "Are the edge weights all equal? If so you do not need a priority queue at all.",
                "Can an edge weight be negative? That single fact decides which algorithm is even "
                        + "correct here.",
                "When you pop a node whose recorded distance is already better than the entry you "
                        + "popped, skip it - that stale-entry check is what keeps this efficient."));

        hints.put("binary-search", List.of(
                "Is there a predicate that is false for a while and then true for the rest of the "
                        + "range? That monotonicity is what makes a search possible.",
                "You may be searching over the answer rather than over the array - 'what is the "
                        + "smallest value that works?' is a binary search too.",
                "Fix the interval convention before writing the loop, and state the invariant. "
                        + "Nearly every bug here is an off-by-one at the boundary."));

        hints.put("two-pointers", List.of(
                "If the array is sorted, what does moving the left pointer do to the sum, and the "
                        + "right one? Each move should rule something out for good.",
                "Convince yourself neither pointer ever needs to move backwards. That is what "
                        + "makes this linear rather than quadratic."));

        hints.put("sliding-window", List.of(
                "What property must hold inside the window? Extend on the right and shrink on the "
                        + "left only while it is violated.",
                "When you shrink, can you jump the left edge straight to where it needs to be "
                        + "instead of stepping? That is often the difference between O(n) and O(n²)."));

        hints.put("greedy", List.of(
                "What is the most obviously good choice at each step? Then try to break it with a "
                        + "counter-example - if you cannot, that is your exchange argument.",
                "Does sorting the input by some key make the right choice obvious? Which key?"));

        hints.put("heap", List.of(
                "Do you repeatedly need the smallest or largest of a changing set? That is the "
                        + "question a heap answers in logarithmic time.",
                "Consider what you push and what you pop. Keeping the heap to size k is often the "
                        + "whole trick."));

        hints.put("hashing", List.of(
                "For each element, what would you like to have already seen? A map from value to "
                        + "index answers that in one pass.",
                "Check before you insert, not after - that is usually what stops an element from "
                        + "matching itself."));

        hints.put("stack", List.of(
                "Which earlier elements are you still waiting to resolve? A stack holds exactly "
                        + "those.",
                "When you pop, what have you just learned about the pair? That is often where the "
                        + "answer is accumulated."));

        hints.put("sorting", List.of(
                "Does sorting make the problem easier to state? Sorting first is free at these "
                        + "constraints compared with the alternatives.",
                "Sort by what, exactly? Choosing the key is usually the whole problem."));

        hints.put("prefix-sum", List.of(
                "If you had the sum of every prefix, could you answer any range in constant time?",
                "Watch the off-by-one on the left boundary; it is the standard error here."));

        hints.put("strings", List.of(
                "What changes as you move one character along? An answer that recomputes from "
                        + "scratch at each position is usually doing n times too much work.",
                "Consider what the character counts alone tell you - sometimes that is enough."));

        hints.put("math", List.of(
                "Try small inputs and look for a pattern before reaching for a formula.",
                "Watch for overflow. At these limits an intermediate product often exceeds a "
                        + "32-bit integer even when the answer does not."));

        return hints;
    }
}
