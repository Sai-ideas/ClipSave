package dev.local.clipvault;

import android.webkit.MimeTypeMap;

import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkSpan;
import org.nibor.autolink.LinkType;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class Article {
    private static final LinkExtractor LINKS =
            LinkExtractor.builder().linkTypes(EnumSet.of(LinkType.URL)).build();

    private static final Parser SOURCE_PARSER = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();

    private Article() {}

    static String render(Draft draft) {
        StringBuilder result = new StringBuilder("# ").append(escapeLiteral(draft.title()));
        HashMap<String, String> names = imageNames(draft);
        for (int index = 0; index < draft.blocks().size(); index++) {
            Draft.Block block = draft.blocks().get(index);
            result.append("\n\n");
            if (block.image()) {
                result.append("![圖片](").append(names.get(block.sourceId())).append(')');
            } else {
                result.append(formatText(block, true));
            }
        }
        return result.append('\n').toString();
    }

    static String exportMarkdown(Draft draft) {
        return linkProse(render(draft));
    }

    static String linkProse(String source) {
        ArrayList<SourceSpan> ranges = new ArrayList<>();
        for (Node node = SOURCE_PARSER.parse(source); node != null; node = nextProseNode(node)) {
            if (!(node instanceof Text text)) continue;
            for (SourceSpan span : text.getSourceSpans()) {
                String raw = source.substring(span.getInputIndex(), span.getInputIndex() + span.getLength());
                for (LinkSpan link : LINKS.extractLinks(raw)) {
                    String url = raw.substring(link.getBeginIndex(), link.getEndIndex());
                    if (Vault.isWebLink(url)) ranges.add(span.subSpan(link.getBeginIndex(), link.getEndIndex()));
                }
            }
        }
        if (ranges.isEmpty()) return source;
        ranges.sort(Comparator.comparingInt(SourceSpan::getInputIndex));
        StringBuilder output = new StringBuilder(source.length() + ranges.size() * 2);
        int end = 0;
        for (SourceSpan range : ranges) {
            int start = range.getInputIndex();
            if (start < end) continue;
            output.append(source, end, start).append('<');
            end = start + range.getLength();
            output.append(source, start, end).append('>');
        }
        return output.append(source, end, source.length()).toString();
    }

    private static Node nextProseNode(Node node) {
        if (!(node instanceof Link) && !(node instanceof Image) && node.getFirstChild() != null) {
            return node.getFirstChild();
        }
        while (node != null && node.getNext() == null) node = node.getParent();
        return node == null ? null : node.getNext();
    }

    static String formatText(Draft.Block block, boolean markdown) {
        String content = block.content();
        Draft.Block.Kind kind = block.kind();
        return switch (kind) {
            case SUBHEADING -> markdown ? "## " + content : content;
            case TITLE -> markdown ? setextHeading(content) : content;
            case DIVIDER -> "---";
            case QUOTE, BULLETS, NUMBERED -> formatLines(content, kind, markdown);
            case CHECKLIST -> Checklist.render(Checklist.items(content), markdown);
            case CODE -> markdown ? fencedCode(content) : content;
            case TEXT -> markdown ? textMarkdown(content, block.format()) : content;
        };
    }

    static String textMarkdown(String content, TextFormat format) {
        return format.markdown(content) ? content : literalMarkdown(content);
    }

    private static String literalMarkdown(String content) {
        String text = normalizeLines(content);
        StringBuilder result = new StringBuilder();
        int start = 0;
        for (LinkSpan span : LINKS.extractLinks(text)) {
            String url = text.substring(span.getBeginIndex(), span.getEndIndex());
            if (!Vault.isWebLink(url)) continue;
            result.append(escapeLiteral(text.substring(start, span.getBeginIndex())));
            result.append('<').append(url).append('>');
            start = span.getEndIndex();
        }
        return result.append(escapeLiteral(text.substring(start))).toString();
    }

    private static String escapeLiteral(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);
            if (symbol == '\n') escaped.append("  ");
            else if ("\\`*_{}[]()#+-.!|>~<&=".indexOf(symbol) >= 0) escaped.append('\\');
            escaped.append(symbol);
        }
        return escaped.toString();
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
            write(zip, "article.md", exportMarkdown(draft).getBytes(StandardCharsets.UTF_8));
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

    static HashMap<String, String> imageNames(Draft draft) {
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
