package com.codearena.ai.complexity;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Estimates asymptotic complexity by reading the shape of the code.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>This is a heuristic, and everything it produces is labelled as one. Complexity analysis in
 * general is undecidable - a loop's bound can depend on a value only known at runtime - so no
 * static reading can be right about every program. What it can do is recognise the handful of
 * shapes that account for most competitive-programming solutions: nested loops over the input,
 * a sort, a binary search, a recursive descent.
 *
 * <p>It exists because the alternative to a heuristic here is not a perfect answer, it is
 * <em>no</em> answer: a language model needs several gigabytes of resident weights, and a
 * project nobody can run without that is a project nobody runs. The model is used when it is
 * available and this fills in when it is not, with the response saying plainly which one spoke.
 *
 * <h2>How it reads the code</h2>
 *
 * <p>Loop nesting depth drives the estimate: a doubly-nested loop over the input is O(n²). That
 * is wrong for a two-pointer sweep, whose inner loop advances a shared index and never restarts
 * - a genuine limitation, called out in the caveat the caller receives rather than hidden.
 *
 * <p>Comments and string literals are stripped first. Without that, the word {@code for} inside
 * a printed message counts as a loop, which is exactly the kind of error that makes a tool like
 * this untrustworthy.
 */
@Component
public class StaticComplexityAnalyser {

    /**
     * Loop headers across the five supported languages.
     *
     * <p>Three alternatives, because the syntaxes genuinely differ. The C-family form puts a
     * parenthesis straight after the keyword; Python does not - {@code for i in range(n):} has a
     * variable there - so matching only on {@code for\s*\(} silently reads every Python solution
     * as having no loops at all. The Python branch is anchored to a line ending in a colon,
     * which is what keeps it from matching a C-style header that happens to span a line.
     */
    private static final Pattern LOOP = Pattern.compile(
            "\\b(for|while|forEach|repeat)\\b\\s*\\("        // for (...) / while (...)
                    + "|\\bdo\\s*\\{"                        // do { ... }
                    + "|^\\s*(for|while)\\b[^;{]*:\\s*$",    // for x in xs:
            Pattern.MULTILINE);

    /**
     * Matches the text immediately before a {@code {} to decide whether it opens a loop body.
     *
     * <p>Separate from {@link #LOOP} because it is applied to a fragment rather than a line, and
     * {@code do} is followed by the brace itself rather than by a parenthesised header.
     */
    private static final Pattern LOOP_HEADER = Pattern.compile(
            "\\b(for|while|forEach|repeat)\\b\\s*\\(|\\bdo\\s*$");

    private static final Pattern SORT = Pattern.compile(
            "\\b(sort|sorted|sort_by|Collections\\.sort|Arrays\\.sort|std::sort|sort\\.Slice)\\b");

    /** The tell-tale midpoint of a hand-written binary search. */
    private static final Pattern BINARY_SEARCH = Pattern.compile(
            "\\b(lower_bound|upper_bound|binarySearch|bisect_left|bisect_right)\\b"
                    + "|\\b(mid|middle)\\b\\s*=\\s*.*(/\\s*2|>>\\s*1)");

    private static final Pattern HASH_CONTAINER = Pattern.compile(
            "\\b(HashMap|HashSet|unordered_map|unordered_set|dict|set\\(|Map<|Set<|make\\(map)\\b");

    private static final Pattern LINEAR_CONTAINER = Pattern.compile(
            "\\b(new int\\[|vector<|ArrayList|\\[\\]int|list\\(|malloc|dp\\s*\\[)\\b");

    private static final Pattern LINE_COMMENT = Pattern.compile("(//|#).*$", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'");

    public ComplexityEstimate analyse(String sourceCode) {
        String code = strip(sourceCode == null ? "" : sourceCode);

        int depth = maxLoopNesting(code);
        boolean sorts = SORT.matcher(code).find();
        boolean halves = BINARY_SEARCH.matcher(code).find();
        boolean recursive = looksRecursive(code);

        List<String> reasons = new ArrayList<>();
        String time = estimateTime(depth, sorts, halves, recursive, reasons);
        String space = estimateSpace(code, depth, reasons);

        return new ComplexityEstimate(time, space, reasons, caveat(depth, recursive));
    }

    private String estimateTime(int depth, boolean sorts, boolean halves, boolean recursive,
                                List<String> reasons) {
        if (depth == 0 && !recursive) {
            reasons.add(sorts
                    ? "No loops of its own, but it sorts, and a sort is O(n log n)."
                    : "No loops and no recursion, so the work does not grow with the input.");
            return sorts ? "O(n log n)" : "O(1)";
        }

        if (depth >= 2) {
            reasons.add(depth + " levels of nested loops, so the inner body runs about n^"
                    + depth + " times.");
            return "O(n^" + depth + ")";
        }

        // Exactly one loop level, or recursion with none.
        if (halves) {
            reasons.add("A single loop that halves its search interval each step - the shape of "
                    + "a binary search.");
            return depth == 0 ? "O(log n)" : "O(n log n)";
        }
        if (sorts) {
            reasons.add("One pass over the input plus a sort; the sort dominates.");
            return "O(n log n)";
        }
        if (depth == 1) {
            reasons.add("A single loop over the input.");
            return "O(n)";
        }

        reasons.add("Recursion with no loop nesting; the bound depends on the branching factor, "
                + "which this cannot see.");
        return "O(n) or worse";
    }

    private String estimateSpace(String code, int depth, List<String> reasons) {
        boolean hashes = HASH_CONTAINER.matcher(code).find();
        boolean allocates = LINEAR_CONTAINER.matcher(code).find();

        if (depth >= 2 && allocates) {
            reasons.add("Allocates a container and has two loop levels, which is the usual shape "
                    + "of a 2-D table.");
            return "O(n^2)";
        }
        if (hashes || allocates) {
            reasons.add(hashes
                    ? "Uses a hash-based container, which grows with the number of keys stored."
                    : "Allocates a container sized by the input.");
            return "O(n)";
        }
        reasons.add("Only a fixed number of variables, none sized by the input.");
        return "O(1)";
    }

    /**
     * Counts the deepest nesting of loop constructs by tracking brace depth at each loop header
     * and again as braces close.
     *
     * <p>Brace counting rather than parsing: a parser per language is a far larger commitment
     * than this feature justifies, and the estimate is explicitly approximate anyway. Python is
     * the known weak spot - no braces to count - so indentation is used there instead.
     */
    private int maxLoopNesting(String code) {
        if (!code.contains("{")) {
            return maxIndentedLoopNesting(code);
        }

        int max = 0;
        int braceDepth = 0;
        int parenDepth = 0;
        // Brace depth at which each still-open loop began.
        List<Integer> openLoops = new ArrayList<>();
        // Text since the last structural delimiter - the candidate loop header for the next '{'.
        StringBuilder pending = new StringBuilder();

        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);

            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            }

            if (c == '{') {
                braceDepth++;
                if (LOOP_HEADER.matcher(pending).find()) {
                    openLoops.add(braceDepth);
                    max = Math.max(max, openLoops.size());
                }
                pending.setLength(0);
            } else if (c == '}') {
                int closingDepth = braceDepth;
                openLoops.removeIf(startDepth -> startDepth >= closingDepth);
                braceDepth = Math.max(0, braceDepth - 1);
                pending.setLength(0);
            } else if (c == ';' && parenDepth == 0) {
                // End of a statement, so anything before it cannot be the header of the next
                // block. The parenthesis check matters: the two semicolons inside `for (a; b; c)`
                // are part of the header, and clearing on them would lose the keyword.
                pending.setLength(0);
            } else {
                pending.append(c);
            }
        }
        return max;
    }

    /** Python and anything else that scopes by indentation. */
    private int maxIndentedLoopNesting(String code) {
        int max = 0;
        List<Integer> openLoops = new ArrayList<>();

        for (String line : code.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            openLoops.removeIf(loopIndent -> loopIndent >= indent);

            if (LOOP.matcher(line).find()) {
                openLoops.add(indent);
                max = Math.max(max, openLoops.size());
            }
        }
        return max;
    }

    /**
     * A function whose body mentions its own name.
     *
     * <p>Crude, and it says so: a function that merely passes its own name as a callback is
     * counted as recursive. It is enough to stop a divide-and-conquer solution being reported
     * as O(1) because it happens to contain no loops.
     */
    private boolean looksRecursive(String code) {
        Pattern declaration = Pattern.compile(
                "\\b(?:def|func|function|int|long|void|double|string|auto|vector<[^>]*>)\\s+"
                        + "([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
        var matcher = declaration.matcher(code);
        while (matcher.find()) {
            String name = matcher.group(1);
            if (name.equals("main")) {
                continue;
            }
            // A call to the same name somewhere after the declaration.
            String rest = code.substring(matcher.end());
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(rest).find()) {
                return true;
            }
        }
        return false;
    }

    private String caveat(int depth, boolean recursive) {
        if (depth >= 2) {
            return "Nested loops are counted structurally, so a two-pointer sweep - where the "
                    + "inner index never restarts - is over-estimated as quadratic.";
        }
        if (recursive) {
            return "Recursive work is recognised but not measured; the true bound depends on how "
                    + "the input shrinks at each call.";
        }
        return "Loop bounds are assumed to grow with the input. A loop over a fixed constant is "
                + "counted as if it were over n.";
    }

    private String strip(String code) {
        return STRING_LITERAL.matcher(
                        LINE_COMMENT.matcher(
                                        BLOCK_COMMENT.matcher(code).replaceAll(" "))
                                .replaceAll(""))
                .replaceAll("\"\"");
    }
}
