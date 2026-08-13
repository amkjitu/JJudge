package com.codearena.judge.real;

import com.codearena.common.domain.Language;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How to build and run a submission in one language.
 *
 * <p>Only the languages the runner image actually carries are here. A language that is missing
 * returns empty rather than a plausible-looking command that fails at run time as {@code CE} -
 * "your code does not compile" is a lie when the truth is "this judge cannot compile it", and it
 * would send someone hunting for a bug in correct code. That distinction is the whole reason the
 * caller gets an {@link Optional}.
 *
 * <h2>Time limits are per-language</h2>
 *
 * <p>A problem's time limit describes an <em>algorithm</em> - "an O(n log n) solution fits, an
 * O(n²) one does not" - and it is invariably calibrated against C++. Charging every language the
 * same wall clock does not measure the algorithm, it measures the runtime's startup: a JVM costs
 * a few hundred milliseconds before {@code main} is entered, and CPython costs its own tax on
 * every loop iteration. A correct Java solution would fail a 2-second limit having spent a
 * quarter of it booting, and the author would be told to optimise code that is already right.
 *
 * <p>So each language carries a multiplier applied to the stated limit, which is what every
 * mainstream judge does. The numbers are the conventional ones rather than anything measured
 * here, and they are deliberately coarse - the point is to stop punishing a language for being
 * that language, not to equalise them exactly.
 *
 * @param sourceFile  what the submission is written to inside the workspace
 * @param compile     argv to compile, or empty for an interpreted language
 * @param run         argv to execute one test case
 * @param timeLimitMultiplier what the problem's stated limit is multiplied by for this language
 */
public record LanguageToolchain(String sourceFile, List<String> compile, List<String> run,
                                double timeLimitMultiplier) {

    private static final Map<Language, LanguageToolchain> SUPPORTED = Map.of(
            // No compile step, so a syntax error surfaces on the first test case as a non-zero
            // exit. That is reported as RTE rather than CE, which is accurate: Python genuinely
            // does not distinguish them - it fails when it reaches the bad line.
            Language.PYTHON, new LanguageToolchain(
                    "main.py",
                    List.of(),
                    List.of("python3", "main.py"),
                    3.0),

            // -O2 because a solution judged against a time limit should be built the way its
            // author expects. -static avoids depending on the runner image's shared libraries
            // resolving under a read-only root.
            //
            // The multiplier is 1.0 because problem limits are written against C++ in the first
            // place. This is the baseline the other two are measured from, not a language that
            // happens to score well.
            Language.CPP, new LanguageToolchain(
                    "main.cpp",
                    List.of("g++", "-std=c++17", "-O2", "-static", "-o", "main", "main.cpp"),
                    List.of("./main"),
                    1.0),

            // The file must be Main.java because javac requires the filename to match the public
            // class, and every submission template declares `public class Main`.
            //
            // The run flags are not decoration:
            //   -XX:+UseSerialGC       a parallel collector starts several GC threads for a
            //                          program that lives half a second, which costs more than it
            //                          collects - and each one counts against the PID limit.
            //   -XX:MaxRAMPercentage   the JVM reads the cgroup limit, but defaults to a quarter
            //                          of it. On a 256 MB container that is a 64 MB heap, which
            //                          fails solutions that are well within the stated limit.
            //   -Xss64m                deep recursion is ordinary in competitive programming, and
            //                          the default stack overflows on inputs the problem allows.
            Language.JAVA, new LanguageToolchain(
                    "Main.java",
                    List.of("javac", "Main.java"),
                    List.of("java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "-Xss64m",
                            "Main"),
                    2.0));

    public static Optional<LanguageToolchain> forLanguage(Language language) {
        return Optional.ofNullable(SUPPORTED.get(language));
    }

    public static boolean supports(Language language) {
        return SUPPORTED.containsKey(language);
    }

    public boolean needsCompiling() {
        return !compile.isEmpty();
    }

    /** The problem's stated limit adjusted for this language. See the class comment. */
    public Duration effectiveTimeLimit(long statedMillis) {
        return Duration.ofMillis(Math.round(statedMillis * timeLimitMultiplier));
    }
}
