package com.codearena.judge.real;

import com.codearena.common.domain.Language;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LanguageToolchain")
class LanguageToolchainTest {

    @Nested
    @DisplayName("language support")
    class Support {

        @Test
        @DisplayName("covers exactly the languages the runner image carries")
        void supportedLanguages() {
            // If this fails, the runner image and this table have drifted apart, and the symptom
            // in production is either a language nobody can submit or one that fails at run time
            // with "javac: not found" reported as a compile error against the user's code.
            assertThat(LanguageToolchain.supports(Language.PYTHON)).isTrue();
            assertThat(LanguageToolchain.supports(Language.CPP)).isTrue();
            assertThat(LanguageToolchain.supports(Language.JAVA)).isTrue();
        }

        @Test
        @DisplayName("returns empty for a language it cannot run, rather than a broken command")
        void unsupportedLanguageIsEmpty() {
            assertThat(LanguageToolchain.forLanguage(Language.GO)).isEmpty();
        }

        @Test
        @DisplayName("only compiled languages have a compile step")
        void compileSteps() {
            assertThat(LanguageToolchain.forLanguage(Language.PYTHON).orElseThrow()
                    .needsCompiling()).isFalse();
            assertThat(LanguageToolchain.forLanguage(Language.CPP).orElseThrow()
                    .needsCompiling()).isTrue();
            assertThat(LanguageToolchain.forLanguage(Language.JAVA).orElseThrow()
                    .needsCompiling()).isTrue();
        }
    }

    @Nested
    @DisplayName("time limits")
    class TimeLimits {

        @Test
        @DisplayName("C++ gets the stated limit, because limits are written against C++")
        void cppIsTheBaseline() {
            assertThat(LanguageToolchain.forLanguage(Language.CPP).orElseThrow()
                    .effectiveTimeLimit(2000).toMillis()).isEqualTo(2000);
        }

        @Test
        @DisplayName("Java and Python get more, so startup cost is not judged as slow code")
        void interpretedAndVmLanguagesGetMore() {
            long java = LanguageToolchain.forLanguage(Language.JAVA).orElseThrow()
                    .effectiveTimeLimit(2000).toMillis();
            long python = LanguageToolchain.forLanguage(Language.PYTHON).orElseThrow()
                    .effectiveTimeLimit(2000).toMillis();

            assertThat(java).isEqualTo(4000);
            assertThat(python).isEqualTo(6000);
        }

        @Test
        @DisplayName("no language is given less time than the problem states")
        void noLanguageIsPenalised() {
            for (Language language : Language.values()) {
                LanguageToolchain.forLanguage(language).ifPresent(toolchain ->
                        assertThat(toolchain.effectiveTimeLimit(1000).toMillis())
                                .as("%s", language)
                                .isGreaterThanOrEqualTo(1000));
            }
        }
    }
}
