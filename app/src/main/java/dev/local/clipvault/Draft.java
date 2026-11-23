package dev.local.clipvault;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

record Draft(String id, String title, List<Block> blocks, long updatedAt, boolean sensitive) {
    static final int MAX_TITLE = 120;
    static final int MAX_BLOCKS = 50;

    Draft {
        blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
    }

    static Draft create(String title) {
        return new Draft(UUID.randomUUID().toString(), title, Collections.emptyList(), System.currentTimeMillis(), false);
    }

    Draft withContent(String title, List<Block> blocks) {
        return new Draft(id, title, blocks, System.currentTimeMillis(), sensitive);
    }

    Draft protect() {
        return sensitive ? this : new Draft(id, title, blocks, updatedAt, true);
    }

    JSONObject toJson() throws Exception {
        JSONArray contents = new JSONArray();
        for (Block block : blocks) contents.put(block.toJson());
        return new JSONObject().put("id", id).put("title", title).put("blocks", contents)
                .put("updatedAt", updatedAt).put("sensitive", sensitive);
    }

    static Draft fromJson(JSONObject json) throws Exception {
        JSONArray contents = json.getJSONArray("blocks");
        ArrayList<Block> blocks = new ArrayList<>(contents.length());
        for (int index = 0; index < contents.length(); index++) {
            blocks.add(Block.fromJson(contents.getJSONObject(index)));
        }
        return new Draft(json.getString("id"), json.getString("title"), blocks,
                json.getLong("updatedAt"), json.getBoolean("sensitive"));
    }

    record Block(String id, String sourceId, String mime, String content, int bytes) {
        static final String PARAGRAPH = "text/plain";
        static final String HEADING = "text/heading";

        static Block text(String mime, String content) {
            return new Block(UUID.randomUUID().toString(), "", mime, content, 0);
        }

        static Block fromClip(Vault.Entry source) {
            return new Block(UUID.randomUUID().toString(), source.id(), source.mime(), source.content(),
                    source.bytes());
        }

        boolean image() { return mime.startsWith("image/"); }

        Block withContent(String value) {
            return new Block(id, sourceId, mime, value, bytes);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("sourceId", sourceId).put("mime", mime)
                    .put("content", content).put("bytes", bytes);
        }

        static Block fromJson(JSONObject json) throws Exception {
            return new Block(json.getString("id"), json.getString("sourceId"), json.getString("mime"),
                    json.getString("content"), json.getInt("bytes"));
        }
    }
}
