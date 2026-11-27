package dev.local.clipvault;

import java.util.regex.Pattern;

enum TextFormat {
    PLAIN, MARKDOWN, LEGACY;

    // Before formats were stored, captures and authored Markdown shared the same text type.
    private static final Pattern LEGACY_MARKUP = Pattern.compile(
            "(?m)^ {0,3}(?:#{1,6}(?:[ \\t]|$)|>[ \\t]|(?:`{3,}|~{3,})|[-+*][ \\t]|\\d{1,9}[.)][ \\t]"
                    + "|(?:-{3,}|={2,})[ \\t]*$)|\\*{1,2}[^*\\s][^*\\n]*\\*{1,2}"
                    + "|(?<![\\p{L}\\d])_{1,2}[^_\\s][^_\\n]*_{1,2}(?![\\p{L}\\d])"
                    + "|`[^`\\n]+`|\\[[^\\[\\]\\n]+\\]\\(");

    boolean markdown(String text) {
        return this == MARKDOWN || (this == LEGACY && LEGACY_MARKUP.matcher(text).find());
    }

    static TextFormat stored(String value, TextFormat fallback) {
        try {
            return value.isEmpty() ? fallback : valueOf(value);
        } catch (IllegalArgumentException error) {
            return fallback;
        }
    }
}
