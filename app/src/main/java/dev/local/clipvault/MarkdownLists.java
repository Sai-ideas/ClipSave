package dev.local.clipvault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownLists {
    private static final Pattern ITEM = Pattern.compile(
            "^([ \t]*(?:>[ \t]*)*)([-+*]|[0-9]{1,9}[.)])([ \t]+)(?:\\[([ xX])\\]([ \t]*))?(.*)$");
    private static final Pattern FENCE = Pattern.compile("^[ \t]*(?:>[ \t]*)*(`{3,}|~{3,})(.*)$");

    record Continuation(int start, int end, String text) {}

    private MarkdownLists() {}

    static Continuation afterEnter(CharSequence body, int newline) {
        int start = newline;
        while (start > 0 && body.charAt(start - 1) != '\n') start--;
        Matcher item = ITEM.matcher(body.subSequence(start, newline));
        if (!item.matches() || insideFence(body, start)) return null;

        String indent = item.group(1);
        String marker = item.group(2);
        String task = item.group(4);
        if (item.group(6).trim().isEmpty()
                && (newline + 1 == body.length() || body.charAt(newline + 1) == '\n')) {
            String quote = indent.contains(">") ? indent : "";
            return new Continuation(start, newline + 1, quote);
        }

        if (marker.length() > 1) {
            int number = Integer.parseInt(marker.substring(0, marker.length() - 1));
            if (number < 999_999_999) marker = (number + 1) + marker.substring(marker.length() - 1);
        }
        String next = indent + marker + item.group(3);
        if (task != null) next += "[ ]" + (item.group(5).isEmpty() ? " " : item.group(5));
        return new Continuation(newline + 1, newline + 1, next);
    }

    private static boolean insideFence(CharSequence body, int limit) {
        char delimiter = 0;
        int length = 0;
        int start = 0;
        for (int index = 0; index < limit; index++) {
            if (body.charAt(index) != '\n') continue;
            Matcher match = FENCE.matcher(body.subSequence(start, index));
            if (match.matches()) {
                String fence = match.group(1);
                if (delimiter == 0) {
                    delimiter = fence.charAt(0);
                    length = fence.length();
                } else if (fence.charAt(0) == delimiter && fence.length() >= length && match.group(2).trim().isEmpty()) {
                    delimiter = 0;
                }
            }
            start = index + 1;
        }
        return delimiter != 0;
    }
}
