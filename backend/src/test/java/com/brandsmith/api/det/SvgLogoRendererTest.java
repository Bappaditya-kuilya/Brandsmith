package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SvgLogoRendererTest {

    private final SvgLogoRenderer renderer = new SvgLogoRenderer();

    private SvgLogoRenderer.LogoSpec wordmark(String text, String fontId) {
        return new SvgLogoRenderer.LogoSpec("wordmark", text, List.of(), fontId, 0);
    }

    @Test
    void escapesScriptInjectionInText() {
        String svg = renderer.render(wordmark("<script>alert(1)</script>", "display-sans"));
        assertFalse(svg.contains("<script>"));
        assertTrue(svg.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(svg.contains("</text>"));
        assertTrue(svg.contains("<svg"));
    }

    @Test
    void escapesQuotesAndAmpersand() {
        String svg = renderer.render(wordmark("Tom & \"Jerry\"", "display-sans"));
        assertTrue(svg.contains("Tom &amp; &quot;Jerry&quot;"), svg);
        assertFalse(svg.contains("\"Jerry\""));
    }

    @Test
    void escapesRawSvgLookingText() {
        String svg = renderer.render(wordmark("<image href=x onerror=alert(1)>", "display-sans"));
        assertFalse(svg.contains("<image"));
        assertTrue(svg.contains("&lt;image"), svg);
    }

    @Test
    void rendersAllShapeTypes() {
        List<SvgLogoRenderer.Shape> shapes = List.of(
                new SvgLogoRenderer.Shape("circle", 40, 40, 20, 20, "#ff0000"),
                new SvgLogoRenderer.Shape("rect", 10, 10, 30, 20, "#00ff00"),
                new SvgLogoRenderer.Shape("line", 0, 0, 50, 10, "#0000ff"),
                new SvgLogoRenderer.Shape("arc", 5, 5, 20, 10, "#000000"));
        SvgLogoRenderer.LogoSpec spec =
                new SvgLogoRenderer.LogoSpec("wordmark", "Nova", shapes, "display-sans", 1.5);
        String svg = renderer.render(spec);
        assertTrue(svg.contains("<circle"), svg);
        assertTrue(svg.contains("<rect"), svg);
        assertTrue(svg.contains("<line"), svg);
        assertTrue(svg.contains("<path"), svg);
        assertTrue(svg.contains("data-font-id=\"display-sans\""));
        assertTrue(svg.contains("letter-spacing=\"1.50\""));
    }

    @Test
    void rejectsDisallowedFontId() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(wordmark("Acme", "not-a-real-font")));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(wordmark("Acme", "<script>")));
    }

    @Test
    void allowsEmptyFontId() {
        String svg = renderer.render(wordmark("Acme", ""));
        assertTrue(svg.contains("<svg"));
        assertFalse(svg.contains("data-font-id"));
    }

    @Test
    void rejectsUnknownTypeAndShape() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new SvgLogoRenderer.LogoSpec("icon", "x", List.of(), "display-sans", 0)));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new SvgLogoRenderer.LogoSpec(
                        "monogram",
                        "",
                        List.of(new SvgLogoRenderer.Shape("polygon", 0, 0, 1, 1, "#fff")),
                        "display-sans",
                        0)));
    }

    @Test
    void rejectsBadFillAndNaN() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new SvgLogoRenderer.LogoSpec(
                        "monogram",
                        "",
                        List.of(new SvgLogoRenderer.Shape("circle", 0, 0, 10, 10, "url(#x)")),
                        "display-sans",
                        0)));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new SvgLogoRenderer.LogoSpec(
                        "monogram",
                        "",
                        List.of(new SvgLogoRenderer.Shape("circle", Double.NaN, 0, 10, 10, "#fff")),
                        "display-sans",
                        0)));
    }

    @Test
    void rejectsTooManyShapes() {
        List<SvgLogoRenderer.Shape> many = java.util.Collections.nCopies(33,
                new SvgLogoRenderer.Shape("circle", 0, 0, 4, 4, "#fff"));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(new SvgLogoRenderer.LogoSpec("monogram", "", many, "display-sans", 0)));
    }

    @Test
    void escapeXmlIsIdempotentOnPlainText() {
        assertEquals("hello", SvgLogoRenderer.escapeXml("hello"));
        assertEquals("a&amp;b", SvgLogoRenderer.escapeXml("a&b"));
    }

    @Test
    void neverAcceptsRawSvgFields() {
        // Grammar has no path/d attributes or nested svg — raw model SVG cannot enter.
        String svg = renderer.render(wordmark("Acme", "display-sans"));
        assertFalse(svg.contains("<foreignObject"));
        assertFalse(svg.contains("javascript:"));
        assertTrue(svg.startsWith("<?xml"));
    }
}
