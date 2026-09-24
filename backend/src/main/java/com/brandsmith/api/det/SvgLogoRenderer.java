package com.brandsmith.api.det;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a constrained logo grammar record to SVG. Never accepts raw SVG from the model.
 * Shapes: circle | rect | line | arc. Text and attributes are XML-escaped. fontId must be allowlisted.
 */
public final class SvgLogoRenderer {

    public record Shape(String shape, double x, double y, double w, double h, String fill) {
    }

    public record LogoSpec(String type, String text, List<Shape> shapes, String fontId, double letterSpacing) {
        public LogoSpec {
            type = type == null ? "" : type;
            text = text == null ? "" : text;
            shapes = shapes == null ? List.of() : List.copyOf(shapes);
            fontId = fontId == null ? "" : fontId;
        }
    }

    private static final Pattern HEX_FILL = Pattern.compile("#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?");
    private static final Pattern FONT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final int MAX_TEXT_LEN = 80;
    private static final int MAX_SHAPES = 32;
    private static final Set<String> FONTS = loadFonts();

    private static final Set<String> SHAPES = Set.of("circle", "rect", "line", "arc");
    private static final Set<String> TYPES = Set.of("wordmark", "monogram");

    public String render(LogoSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec is null");
        }
        if (!TYPES.contains(spec.type())) {
            throw new IllegalArgumentException("invalid type: " + spec.type());
        }
        if (spec.shapes().size() > MAX_SHAPES) {
            throw new IllegalArgumentException("too many shapes");
        }
        if (!spec.fontId().isEmpty() && !FONTS.contains(spec.fontId())) {
            throw new IllegalArgumentException("fontId not in allowlist: " + spec.fontId());
        }
        if (spec.text().length() > MAX_TEXT_LEN) {
            throw new IllegalArgumentException("text too long");
        }
        if (!Double.isFinite(spec.letterSpacing()) || spec.letterSpacing() < -5 || spec.letterSpacing() > 20) {
            throw new IllegalArgumentException("letterSpacing out of range: " + spec.letterSpacing());
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 320 80\" role=\"img\" aria-label=\"logo\">");

        if (!spec.fontId().isEmpty()) {
            sb.append("<g data-font-id=\"").append(escapeAttr(spec.fontId())).append("\">");
        }
        for (Shape s : spec.shapes()) {
            appendShape(sb, s);
        }
        if (!spec.text().isEmpty()) {
            sb.append("<text x=\"160\" y=\"48\" text-anchor=\"middle\" font-family=\"system-ui,sans-serif\"")
                    .append(" font-size=\"24\" letter-spacing=\"").append(format(spec.letterSpacing())).append("\">")
                    .append(escapeXml(spec.text()))
                    .append("</text>");
        }
        if (!spec.fontId().isEmpty()) {
            sb.append("</g>");
        }
        sb.append("</svg>");
        return sb.toString();
    }

    private void appendShape(StringBuilder sb, Shape s) {
        if (s == null || !SHAPES.contains(s.shape())) {
            throw new IllegalArgumentException("invalid shape: " + (s == null ? "null" : s.shape()));
        }
        requireFinite(s);
        String fill = s.fill() == null || s.fill().isEmpty() ? null : s.fill();
        if (fill != null && !HEX_FILL.matcher(fill).matches()) {
            throw new IllegalArgumentException("invalid fill: " + fill);
        }
        switch (s.shape()) {
            case "circle" -> sb.append("<circle cx=\"").append(format(s.x()))
                    .append("\" cy=\"").append(format(s.y()))
                    .append("\" r=\"").append(format(s.w() / 2)).append('"');
            case "rect" -> sb.append("<rect x=\"").append(format(s.x()))
                    .append("\" y=\"").append(format(s.y()))
                    .append("\" width=\"").append(format(s.w()))
                    .append("\" height=\"").append(format(s.h())).append('"');
            case "line" -> sb.append("<line x1=\"").append(format(s.x()))
                    .append("\" y1=\"").append(format(s.y()))
                    .append("\" x2=\"").append(format(s.x() + s.w()))
                    .append("\" y2=\"").append(format(s.y() + s.h()))
                    .append("\" stroke-width=\"2\"");
            case "arc" -> sb.append("<path d=\"M ").append(format(s.x()))
                    .append(' ').append(format(s.y()))
                    .append(" A ").append(format(s.w()))
                    .append(' ').append(format(s.h()))
                    .append(" 0 0 1 ").append(format(s.x() + s.w()))
                    .append(' ').append(format(s.y() + s.h())).append('"');
            default -> throw new IllegalArgumentException("invalid shape: " + s.shape());
        }
        if (fill != null) {
            if ("line".equals(s.shape())) {
                sb.append(" stroke=\"").append(fill).append('"');
            } else {
                sb.append(" fill=\"").append(fill).append('"');
            }
        }
        sb.append("/>");
    }

    private static void requireFinite(Shape s) {
        if (!Double.isFinite(s.x()) || !Double.isFinite(s.y())
                || !Double.isFinite(s.w()) || !Double.isFinite(s.h())) {
            throw new IllegalArgumentException("shape coordinates must be finite");
        }
        if (s.w() < 0 || s.h() < 0) {
            throw new IllegalArgumentException("shape size must be non-negative");
        }
    }

    static String escapeXml(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            switch (ch) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String escapeAttr(String raw) {
        return escapeXml(raw);
    }

    private static String format(double v) {
        if (v == (long) v) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%.2f", v);
    }

    public static boolean isAllowedFont(String fontId) {
        return fontId != null && FONTS.contains(fontId);
    }

    private static Set<String> loadFonts() {
        Set<String> fonts = new LinkedHashSet<>();
        try (InputStream in = SvgLogoRenderer.class.getResourceAsStream("/fonts-allowed.txt")) {
            if (in == null) {
                return fonts;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#") && FONT_ID.matcher(t).matches()) {
                        fonts.add(t);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read fonts-allowed.txt", e);
        }
        return fonts;
    }
}
