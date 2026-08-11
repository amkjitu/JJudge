package com.codearena.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarkdownRenderer")
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Nested
    @DisplayName("rendering")
    class Rendering {

        @Test
        @DisplayName("renders headings, emphasis and inline code")
        void rendersBasicMarkdown() {
            String html = renderer.toHtml("## Constraints\n\nUse `a[i]` and *care*.");

            assertThat(html)
                    .contains("<h2>Constraints</h2>")
                    .contains("<code>a[i]</code>")
                    .contains("<em>care</em>");
        }

        @Test
        @DisplayName("renders fenced code blocks")
        void rendersCodeBlocks() {
            String html = renderer.toHtml("```\nint main() {}\n```");

            assertThat(html).contains("<pre><code>").contains("int main() {}");
        }

        @Test
        @DisplayName("empty and null input render as nothing rather than failing")
        void handlesAbsentInput() {
            assertThat(renderer.toHtml(null)).isEmpty();
            assertThat(renderer.toHtml("")).isEmpty();
            assertThat(renderer.toHtml("   \n  ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("escaping")
    class Escaping {

        @Test
        @DisplayName("a script tag in the source becomes text, not markup")
        void escapesRawHtml() {
            // The template renders this with th:utext, which escapes nothing. If raw HTML were
            // passed through here, a statement would be a stored-XSS vector the moment anything
            // but the bundled seed file could write one.
            String html = renderer.toHtml("Careful: <script>alert('xss')</script>");

            assertThat(html).doesNotContain("<script>");
            assertThat(html).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("an inline event handler is escaped too")
        void escapesInlineHandlers() {
            String html = renderer.toHtml("<img src=x onerror=\"alert(1)\">");

            assertThat(html).doesNotContain("<img");
            assertThat(html).contains("&lt;img");
        }

        @Test
        @DisplayName("a javascript: link is not turned into an anchor href")
        void doesNotEmitJavascriptUrls() {
            String html = renderer.toHtml("[click](javascript:alert(1))");

            assertThat(html).doesNotContain("href=\"javascript:");
        }
    }
}
