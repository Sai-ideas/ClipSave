package dev.local.clipvault;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Checklist {
    private static final int MAX_ITEMS = 50;
    private static final Pattern MARKER = Pattern.compile("^(?:[-+*]\\h+)?\\[([ xX])](?:\\h+(.*))?$");

    record Item(String text, boolean checked) {}

    private Checklist() {}

    static List<Item> items(String content) {
        ArrayList<Item> items = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String text = line.trim();
            Matcher marker = MARKER.matcher(text);
            boolean checked = false;
            if (marker.matches()) {
                checked = !marker.group(1).equals(" ");
                text = marker.group(2) == null ? "" : marker.group(2).trim();
            }
            if (!text.isEmpty()) items.add(new Item(text, checked));
        }
        return items;
    }

    static String normalize(String input) {
        List<Item> items = items(input);
        if (items.isEmpty()) throw new IllegalArgumentException("至少輸入一個待辦項目");
        if (items.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("每個待辦清單最多 " + MAX_ITEMS + " 項");
        }
        String content = render(items, true);
        if (content.length() > Vault.MAX_TEXT) throw new IllegalArgumentException("待辦清單文字過長");
        return content;
    }

    static String render(List<Item> items, boolean markdown) {
        StringBuilder text = new StringBuilder();
        for (Item item : items) {
            if (text.length() > 0) text.append('\n');
            if (markdown) text.append(item.checked() ? "- [x] " : "- [ ] ");
            else text.append(item.checked() ? "☑ " : "☐ ");
            text.append(item.text());
        }
        return text.toString();
    }
}
