package com.codearena.judge.real;

/**
 * Decides whether a submission's output matches what was expected.
 *
 * <h2>Why this is not {@code expected.equals(actual)}</h2>
 *
 * <p>Because it would fail nearly every correct solution. {@code System.out.println} adds a
 * trailing newline, {@code print()} in Python does too, and a program that writes its answers in
 * a loop leaves one at the end. Insisting on an exact byte match makes the judge a test of
 * whether somebody remembered to suppress the last newline, which is not the skill being
 * assessed.
 *
 * <p>So the comparison is line-based, with trailing whitespace stripped from each line and
 * trailing blank lines ignored. What it deliberately does <em>not</em> ignore:
 *
 * <ul>
 *   <li><b>Leading whitespace</b>, which is significant when a problem asks for a grid or an
 *       indented structure.</li>
 *   <li><b>Whitespace inside a line.</b> {@code "1 2"} and {@code "1  2"} are different answers;
 *       collapsing them would accept output no other judge accepts.</li>
 *   <li><b>Line order and count</b>, obviously.</li>
 * </ul>
 *
 * <p>This is the conventional behaviour of ICPC-style checkers, and matching convention matters
 * more than any individual choice here: a competitor's instinct about what will be accepted is
 * itself part of the skill.
 */
public final class OutputComparator {

    private OutputComparator() {
    }

    public static boolean matches(String expected, String actual) {
        return normalise(expected).equals(normalise(actual));
    }

    /**
     * Splits into lines, strips trailing whitespace from each, and drops trailing blank lines.
     *
     * <p>{@code \r} is handled because a submission may well have been written on Windows, and
     * failing somebody for their editor's line endings would be indefensible.
     */
    private static String normalise(String text) {
        if (text == null) {
            return "";
        }

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        int last = lines.length - 1;
        while (last >= 0 && stripTrailing(lines[last]).isEmpty()) {
            last--;
        }

        StringBuilder normalised = new StringBuilder();
        for (int i = 0; i <= last; i++) {
            if (i > 0) {
                normalised.append('\n');
            }
            normalised.append(stripTrailing(lines[i]));
        }
        return normalised.toString();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
