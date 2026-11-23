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
                if (markdown && block.mime().equals(Draft.Block.HEADING)) {
                    result.append("## ").append(block.content());
                } else if (markdown && Vault.isWebLink(block.content())) {
                    result.append('<').append(block.content().trim()).append('>');
                } else {
                    result.append(block.content());
                }
            }
        }
        return result.append('\n').toString();
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
