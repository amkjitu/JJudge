package com.codearena.api.service;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

/**
 * Renders problem prose to HTML.
 *
 * <h2>Two separate defences, both off by default</h2>
 *
 * <p>{@code escapeHtml(true)} turns HTML embedded in the Markdown into visible text rather than
 * markup, so a {@code <script>} tag in a statement renders as the characters of a script tag.
 *
 * <p>{@code sanitizeUrls(true)} is the one that is easy to miss. Escaping raw HTML does nothing
 * about <em>link destinations</em>, which are Markdown's own syntax and are written into an
 * {@code href} verbatim - so {@code [click](javascript:alert(1))} survives an escaping renderer
 * intact. The two settings cover different halves of the same hole, and a test asserting only
 * the first would have passed while the second was wide open.
 *
 * <p>The alternative is to trust the source. Today statements come from a bundled resource, so
 * that would be true; the moment an admin can write one, or a statement is imported from
 * elsewhere, it stops being true and nothing about this class would look any different. The
 * template renders the output with {@code th:utext}, which escapes nothing, so whatever
 * protection exists has to be here.
 *
 * <p>The parser and renderer are documented as thread-safe and are built once.
 */
@Component
public class MarkdownRenderer {

    private final Parser parser = Parser.builder().build();

    private final HtmlRenderer renderer = HtmlRenderer.builder()
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    /** Renders Markdown to HTML, or the empty string for no input. */
    public String toHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        return renderer.render(parser.parse(markdown));
    }
}
