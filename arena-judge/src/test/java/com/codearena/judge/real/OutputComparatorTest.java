package com.codearena.judge.real;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparison that decides AC from WA.
 *
 * <p>Worth testing this hard because both kinds of mistake are severe and neither is visible
 * from the outside. Too strict and correct solutions are rejected over a trailing newline, which
 * is what happened to the submission that prompted building a real judge at all. Too lenient and
 * the judge accepts answers no other judge would, which is worse - it teaches the wrong habit.
 */
@DisplayName("OutputComparator")
class OutputComparatorTest {

    @Nested
    @DisplayName("accepts differences that are not the user's fault")
    class Accepts {

        @Test
        @DisplayName("a trailing newline, which every println adds")
        void trailingNewline() {
            assertThat(OutputComparator.matches("42", "42\n")).isTrue();
            assertThat(OutputComparator.matches("42\n", "42")).isTrue();
        }

        @Test
        @DisplayName("several trailing newlines")
        void repeatedTrailingNewlines() {
            assertThat(OutputComparator.matches("42", "42\n\n\n")).isTrue();
        }

        @Test
        @DisplayName("trailing spaces on a line, which a print loop leaves behind")
        void trailingSpaces() {
            assertThat(OutputComparator.matches("1 2 3", "1 2 3   ")).isTrue();
        }

        @Test
        @DisplayName("trailing whitespace on every line of a multi-line answer")
        void trailingSpacesOnEveryLine() {
            assertThat(OutputComparator.matches("1 4\n4 4", "1 4  \n4 4  \n")).isTrue();
        }

        @Test
        @DisplayName("Windows line endings")
        void carriageReturns() {
            // Failing somebody because of their editor would be indefensible.
            assertThat(OutputComparator.matches("a\nb", "a\r\nb\r\n")).isTrue();
        }

        @Test
        @DisplayName("empty output against empty expected")
        void bothEmpty() {
            assertThat(OutputComparator.matches("", "")).isTrue();
            assertThat(OutputComparator.matches("", "\n")).isTrue();
            assertThat(OutputComparator.matches(null, "")).isTrue();
        }
    }

    @Nested
    @DisplayName("rejects differences that are genuinely wrong answers")
    class Rejects {

        @Test
        @DisplayName("a different value")
        void differentValue() {
            assertThat(OutputComparator.matches("42", "43")).isFalse();
        }

        @Test
        @DisplayName("whitespace inside a line")
        void internalWhitespace() {
            // "1 2" and "1  2" are different answers. Collapsing them would accept output no
            // other judge accepts, which teaches a habit that fails elsewhere.
            assertThat(OutputComparator.matches("1 2", "1  2")).isFalse();
        }

        @Test
        @DisplayName("leading whitespace, which is significant in a grid")
        void leadingWhitespace() {
            assertThat(OutputComparator.matches("ab", " ab")).isFalse();
        }

        @Test
        @DisplayName("a missing line")
        void missingLine() {
            assertThat(OutputComparator.matches("1\n2", "1")).isFalse();
        }

        @Test
        @DisplayName("an extra non-blank line")
        void extraLine() {
            assertThat(OutputComparator.matches("1", "1\n2")).isFalse();
        }

        @Test
        @DisplayName("a blank line in the middle is not the same as no blank line")
        void internalBlankLine() {
            assertThat(OutputComparator.matches("1\n2", "1\n\n2")).isFalse();
        }

        @Test
        @DisplayName("lines in the wrong order")
        void reordered() {
            assertThat(OutputComparator.matches("1\n2", "2\n1")).isFalse();
        }

        @Test
        @DisplayName("output when none was expected")
        void unexpectedOutput() {
            assertThat(OutputComparator.matches("", "42")).isFalse();
        }
    }

    @Nested
    @DisplayName("the case that started this")
    class TheReportedCase {

        @Test
        @DisplayName("the reverse-the-words sample, printed the way Java prints it")
        void reverseTheWords() {
            // System.out.println adds the newline; the expected output does not carry one. An
            // exact match would have failed this, and the user would have been right to complain
            // a second time.
            assertThat(OutputComparator.matches("blue is sky the", "blue is sky the\n")).isTrue();
        }
    }
}
