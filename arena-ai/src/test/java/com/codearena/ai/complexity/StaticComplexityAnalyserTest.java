package com.codearena.ai.complexity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The static analyser, which is what answers when no model is available.
 *
 * <p>These assert the estimate on realistic solutions rather than on contrived snippets - the
 * shapes it has to recognise are the ones people actually submit. The known-wrong cases are
 * asserted too, in {@link Limitations}: a heuristic that quietly changes which cases it gets
 * wrong is a heuristic nobody can rely on.
 */
@DisplayName("StaticComplexityAnalyser")
class StaticComplexityAnalyserTest {

    private final StaticComplexityAnalyser analyser = new StaticComplexityAnalyser();

    @Nested
    @DisplayName("time complexity")
    class Time {

        @Test
        @DisplayName("a single loop over the input is linear")
        void singleLoop() {
            String code = """
                    int main() {
                        int n; cin >> n;
                        long total = 0;
                        for (int i = 0; i < n; i++) {
                            total += i;
                        }
                        cout << total;
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n)");
        }

        @Test
        @DisplayName("two nested loops are quadratic")
        void nestedLoops() {
            String code = """
                    int main() {
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j < n; j++) {
                                sum += a[i] * a[j];
                            }
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n^2)");
        }

        @Test
        @DisplayName("three nested loops are cubic")
        void tripleNested() {
            String code = """
                    void solve() {
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j < n; j++) {
                                for (int k = 0; k < n; k++) {
                                    d[i][j] = min(d[i][j], d[i][k] + d[k][j]);
                                }
                            }
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n^3)");
        }

        @Test
        @DisplayName("sequential loops are linear, not quadratic")
        void sequentialLoopsAreNotNested() {
            // The distinction the brace tracking exists for. Counting loop keywords alone would
            // call this quadratic, which is the most obvious way to get this wrong.
            String code = """
                    int main() {
                        for (int i = 0; i < n; i++) {
                            read(a[i]);
                        }
                        for (int i = 0; i < n; i++) {
                            total += a[i];
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n)");
        }

        @Test
        @DisplayName("a loop that halves its interval is logarithmic")
        void binarySearch() {
            String code = """
                    int search(vector<int>& a, int target) {
                        int lo = 0, hi = a.size();
                        while (lo < hi) {
                            int mid = lo + (hi - lo) / 2;
                            if (a[mid] < target) lo = mid + 1; else hi = mid;
                        }
                        return lo;
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n log n)");
        }

        @Test
        @DisplayName("sorting dominates a single pass")
        void sortDominates() {
            String code = """
                    int main() {
                        sort(v.begin(), v.end());
                        for (int i = 0; i < n; i++) {
                            answer += v[i];
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n log n)");
        }

        @Test
        @DisplayName("straight-line code is constant")
        void noLoops() {
            String code = """
                    int main() {
                        int a, b;
                        cin >> a >> b;
                        cout << a + b;
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(1)");
        }

        @Test
        @DisplayName("nesting is counted by position, not by line")
        void nestedLoopsOnOneLine() {
            // Every other test here is written in tidy multi-line style, and a first version of
            // this analyser tracked "does this line open a loop?" as one flag per line - so two
            // loop headers on the same line counted as one, and this reported O(n). The bug only
            // showed up end to end, against a submission that happened to be written compactly.
            String code = "int main(){ for(int i=0;i<n;i++){ for(int j=0;j<n;j++){ s+=a[i]*a[j]; } } }";

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n^2)");
        }

        @Test
        @DisplayName("a semicolon inside a for header does not break the header")
        void semicolonsInsideForHeader() {
            // The scan clears its buffer at each statement end, so the two semicolons inside
            // `for (a; b; c)` would discard the keyword if parenthesis depth were not tracked.
            String code = "void f(){ for (int i = 0; i < n; i++) { total += i; } }";

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n)");
        }

        @Test
        @DisplayName("a loop header split across lines is still a loop")
        void headerSplitAcrossLines() {
            String code = """
                    int main()
                    {
                        for (int i = 0; i < n; i++)
                        {
                            total += i;
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n)");
        }

        @Test
        @DisplayName("an if inside a loop does not add a level")
        void conditionalIsNotALoop() {
            String code = "int main(){ for(int i=0;i<n;i++){ if (a[i] > 0) { total += a[i]; } } }";

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n)");
        }

        @Test
        @DisplayName("Python indentation is read like braces")
        void pythonNesting() {
            String code = """
                    def solve(a):
                        best = 0
                        for i in range(len(a)):
                            for j in range(i, len(a)):
                                best = max(best, a[i] + a[j])
                        return best
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n^2)");
        }
    }

    @Nested
    @DisplayName("space complexity")
    class Space {

        @Test
        @DisplayName("a hash container is linear space")
        void hashContainer() {
            String code = """
                    int main() {
                        unordered_map<int,int> seen;
                        for (int i = 0; i < n; i++) {
                            seen[a[i]] = i;
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).spaceComplexity()).isEqualTo("O(n)");
        }

        @Test
        @DisplayName("a table filled by two nested loops is quadratic space")
        void twoDimensionalTable() {
            String code = """
                    int solve() {
                        vector<vector<int>> dp(n + 1, vector<int>(m + 1));
                        for (int i = 1; i <= n; i++) {
                            for (int j = 1; j <= m; j++) {
                                dp[i][j] = dp[i-1][j-1] + 1;
                            }
                        }
                        return dp[n][m];
                    }
                    """;

            assertThat(analyser.analyse(code).spaceComplexity()).isEqualTo("O(n^2)");
        }

        @Test
        @DisplayName("a few scalars are constant space")
        void scalarsOnly() {
            String code = """
                    int main() {
                        long best = 0, running = 0;
                        for (int i = 0; i < n; i++) {
                            running = max(0L, running) + x;
                            best = max(best, running);
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).spaceComplexity()).isEqualTo("O(1)");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {

        @Test
        @DisplayName("the word 'for' inside a comment is not a loop")
        void ignoresComments() {
            String code = """
                    int main() {
                        // for each element we would normally loop, but not here
                        /* for (int i = 0; i < n; i++) { old approach } */
                        cout << 0;
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(1)");
        }

        @Test
        @DisplayName("the word 'for' inside a string is not a loop")
        void ignoresStringLiterals() {
            String code = """
                    int main() {
                        cout << "for (int i = 0; i < n; i++) is the usual shape";
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(1)");
        }

        @Test
        @DisplayName("empty and null input are answered, not thrown at")
        void handlesAbsentInput() {
            assertThat(analyser.analyse(null).timeComplexity()).isEqualTo("O(1)");
            assertThat(analyser.analyse("").timeComplexity()).isEqualTo("O(1)");
        }

        @Test
        @DisplayName("every estimate carries its reasoning and a caveat")
        void alwaysExplainsItself() {
            // The estimate is a guess. Handing someone a bare "O(n^2)" with no reasoning invites
            // them to trust it more than it deserves.
            ComplexityEstimate estimate = analyser.analyse("for (int i=0;i<n;i++) { x++; }");

            assertThat(estimate.reasons()).isNotEmpty();
            assertThat(estimate.caveat()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("known limitations")
    class Limitations {

        @Test
        @DisplayName("a two-pointer sweep is over-estimated as quadratic, and says so")
        void twoPointerIsOverEstimated() {
            // Genuinely linear - the inner index never restarts - but structurally it is a loop
            // inside a loop, and no amount of pattern matching fixes that without real analysis.
            // Asserted rather than ignored so the caveat stays honest about this exact case.
            String code = """
                    int main() {
                        int j = 0;
                        for (int i = 0; i < n; i++) {
                            while (j < n && a[j] - a[i] <= k) {
                                j++;
                            }
                            best = max(best, j - i);
                        }
                    }
                    """;

            ComplexityEstimate estimate = analyser.analyse(code);

            assertThat(estimate.timeComplexity()).isEqualTo("O(n^2)");
            assertThat(estimate.caveat()).contains("two-pointer");
        }

        @Test
        @DisplayName("a loop over a fixed constant still counts as n")
        void constantBoundLoopCountsAsLinear() {
            String code = """
                    int main() {
                        for (int i = 0; i < 26; i++) {
                            counts[i] = 0;
                        }
                    }
                    """;

            assertThat(analyser.analyse(code).timeComplexity()).isEqualTo("O(n)");
            assertThat(analyser.analyse(code).caveat()).contains("fixed constant");
        }
    }
}
