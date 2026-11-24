package dev.local.clipvault;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

record Draft(String id, String title, List<Block> blocks, long updatedAt) {
    static final int MAX_TITLE = 120;
    static final int MAX_BLOCKS = 50;

    Draft {
        blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
    }

    static Draft create(String title) {
        return new Draft(UUID.randomUUID().toString(), title, Collections.emptyList(), System.currentTimeMillis());
    }

    Draft withContent(String title, List<Block> blocks) {
        return new Draft(id, title, blocks, Math.max(System.currentTimeMillis(), updatedAt + 1));
    }

    boolean sensitive() {
        return blocks.stream().anyMatch(Block::sensitive);
    }

    Draft protectSources(Set<String> ids) {
        ArrayList<Block> next = new ArrayList<>(blocks);
        for (int index = 0; index < blocks.size(); index++) {
            Block block = blocks.get(index);
            if (!block.sensitive() && ids.contains(block.sourceId())) next.set(index, block.protect());
        }
        return next.equals(blocks) ? this : new Draft(id, title, next, updatedAt);
    }

    JSONObject toJson() throws Exception {
        JSONArray contents = new JSONArray();
        for (Block block : blocks) contents.put(block.toJson());
        return new JSONObject().put("id", id).put("title", title).put("blocks", contents)
                .put("updatedAt", updatedAt).put("sensitive", sensitive());
    }

    static Draft fromJson(JSONObject json) throws Exception {
        JSONArray contents = json.getJSONArray("blocks");
        boolean legacySensitive = json.getBoolean("sensitive");
        ArrayList<Block> blocks = new ArrayList<>(contents.length());
        for (int index = 0; index < contents.length(); index++) {
            blocks.add(Block.fromJson(contents.getJSONObject(index), legacySensitive));
        }
        return new Draft(json.getString("id"), json.getString("title"), blocks,
                json.getLong("updatedAt"));
    }

    record Block(String id, String sourceId, String mime, String content, int bytes, boolean sensitive) {
        static final String PARAGRAPH = "text/plain";
        static final String HEADING = "text/heading";

        enum Kind {
            TEXT(PARAGRAPH, "一般段落", "輸入段落", true),
            SUBHEADING(HEADING, "小標（##）", "輸入小標", false),
            TITLE("text/setext", "標題（===）", "輸入標題", false),
            QUOTE("text/quote", "引用", "輸入引用文字", true),
            BULLETS("text/bullets", "項目清單", "每行輸入一個項目", true),
            CHECKLIST("text/checklist", "待辦清單", "每行一項，輸入 \\[x] 可保留字面符號", true),
            NUMBERED("text/numbered", "編號清單", "每行輸入一個項目", true),
            CODE("text/code", "程式碼區塊", "輸入程式碼", true),
            DIVIDER("text/divider", "分隔線（---）", "", false);

            final String mime;
            final String label;
            final String hint;
            final boolean multiline;

            Kind(String mime, String label, String hint, boolean multiline) {
                this.mime = mime;
                this.label = label;
                this.hint = hint;
                this.multiline = multiline;
            }
        }

        static Block text(String mime, String content) {
            return new Block(UUID.randomUUID().toString(), "", mime, content, 0, false);
        }

        static Block fromClip(Vault.Entry source) {
            return new Block(UUID.randomUUID().toString(), source.id(), source.mime(), source.content(),
                    source.bytes(), source.sensitive());
        }

        boolean image() { return mime.startsWith("image/"); }

        Kind kind() {
            for (Kind kind : Kind.values()) {
                if (kind.mime.equals(mime)) return kind;
            }
            return Kind.TEXT;
        }

        Block withContent(String value) {
            return new Block(id, sourceId, mime, value, bytes, sensitive);
        }

        Block protect() {
            return new Block(id, sourceId, mime, content, bytes, true);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("sourceId", sourceId).put("mime", mime)
                    .put("content", content).put("bytes", bytes).put("sensitive", sensitive);
        }

        static Block fromJson(JSONObject json, boolean legacySensitive) throws Exception {
            String sourceId = json.getString("sourceId");
            // Older protected drafts did not distinguish copied public blocks from private ones.
            boolean sensitive = json.optBoolean("sensitive", legacySensitive && !sourceId.isEmpty());
            return new Block(json.getString("id"), sourceId, json.getString("mime"),
                    json.getString("content"), json.getInt("bytes"), sensitive);
        }
    }
}
