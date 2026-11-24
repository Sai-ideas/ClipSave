package dev.local.clipvault;

import android.webkit.MimeTypeMap;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Article {
    private Article() {}

    static String render(Draft draft, boolean markdown) {
        StringBuilder result = new StringBuilder(markdown ? "# " : "").append(draft.title());
        HashMap<String, String> names = markdown ? imageNames(draft) : new HashMap<>();
        for (int index = 0; index < draft.blocks().size(); index++) {
            Draft.Block block = draft.blocks().get(index);
            result.append("\n\n");
            if (block.image()) {
                if (markdown) result.append("![圖片](").append(names.get(block.sourceId())).append(')');
                else result.append("[圖片：").append(block.content()).append(']');
            } else {
                result.append(formatText(block, markdown));
            }
        }
        return result.append('\n').toString();
    }

    static String formatText(Draft.Block block, boolean markdown) {
        String content = block.content();
        Draft.Block.Kind kind = block.kind();
        return switch (kind) {
            case SUBHEADING -> markdown ? "## " + content : content;
            case TITLE -> markdown ? setextHeading(content) : content;
            case DIVIDER -> "---";
            case QUOTE, BULLETS, NUMBERED -> formatLines(content, kind, markdown);
            case CODE -> markdown ? fencedCode(content) : content;
            case TEXT -> markdown && Vault.isWebLink(content) ? "<" + content.trim() + ">" : content;
        };
    }

    private static String setextHeading(String content) {
        String heading = normalizeLines(content).replace('\n', ' ').trim();
        if (heading.matches("^[0-9]{1,9}[.)](\\s.*)?$")) {
            heading = heading.replaceFirst("[.)]", "\\\\$0");
        } else if (!heading.isEmpty() && "#>-+*`~<[=_".indexOf(heading.charAt(0)) >= 0) {
            heading = "\\" + heading;
        }
        return heading + "\n===";
    }

    private static String formatLines(String content, Draft.Block.Kind kind, boolean markdown) {
        StringBuilder result = new StringBuilder();
        int number = 1;
        for (String line : normalizeLines(content).split("\n", -1)) {
            if (kind != Draft.Block.Kind.QUOTE && line.trim().isEmpty()) continue;
            if (result.length() > 0) result.append('\n');
            switch (kind) {
                case QUOTE -> result.append(line.isEmpty() ? ">" : "> " + line);
                case NUMBERED -> result.append(number++).append(". ").append(line.trim());
                case BULLETS -> result.append(markdown ? "- " : "• ").append(line.trim());
                default -> throw new IllegalArgumentException("Unsupported line format");
            }
        }
        return result.toString();
    }

    private static String fencedCode(String content) {
        int longest = 0;
        int run = 0;
        for (int index = 0; index < content.length(); index++) {
            run = content.charAt(index) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        char[] fenceChars = new char[Math.max(3, longest + 1)];
        Arrays.fill(fenceChars, '`');
        String fence = new String(fenceChars);
        String normalized = normalizeLines(content);
        return fence + "\n" + normalized + (normalized.endsWith("\n") ? "" : "\n") + fence;
    }

    private static String normalizeLines(String content) {
        return content.replace("\r\n", "\n").replace('\r', '\n');
    }

    static void writeZip(Vault vault, Draft draft, OutputStream destination) throws Exception {
        HashMap<String, String> names = imageNames(draft);
        HashSet<String> written = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            write(zip, "article.md", render(draft, true).getBytes(StandardCharsets.UTF_8));
            for (int index = 0; index < draft.blocks().size(); index++) {
                Draft.Block block = draft.blocks().get(index);
                if (!block.image() || !written.add(block.sourceId())) continue;
                byte[] bytes = vault.draftImage(draft, block);
                try {
                    write(zip, names.get(block.sourceId()), bytes);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
        }
    }

    private static HashMap<String, String> imageNames(Draft draft) {
        HashMap<String, String> names = new HashMap<>();
        for (int index = 0; index < draft.blocks().size(); index++) {
            Draft.Block block = draft.blocks().get(index);
            if (block.image() && !names.containsKey(block.sourceId())) {
                names.put(block.sourceId(), imageName(block, index));
            }
        }
        return names;
    }

    private static String imageName(Draft.Block block, int index) {
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(block.mime());
        if (extension == null || !extension.matches("[a-z0-9]+")) extension = "img";
        return String.format(Locale.ROOT, "images/%02d.%s", index + 1, extension);
    }

    private static void write(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
