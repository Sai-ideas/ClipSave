package dev.local.clipvault;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

record Draft(String id, String title, List<Block> blocks, long updatedAt,
             boolean manualLock, List<Group> groups) {
    static final int MAX_TITLE = 120;
    static final int MAX_BLOCKS = 50;

    Draft {
        blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
        HashSet<String> blockIds = new HashSet<>();
        for (Block block : blocks) blockIds.add(block.id());
        ArrayList<Group> remaining = new ArrayList<>();
        for (Group group : groups) {
            List<String> members = group.members().stream().filter(blockIds::contains)
                    .collect(java.util.stream.Collectors.toList());
            if (!members.isEmpty()) remaining.add(new Group(group.id(), group.title(), group.color(), members,
                    group.collapsed()));
        }
        groups = Collections.unmodifiableList(remaining);
    }

    static Draft create(String title) {
        return new Draft(UUID.randomUUID().toString(), title, Collections.emptyList(), System.currentTimeMillis(),
                false, Collections.emptyList());
    }

    Draft withContent(String title, List<Block> blocks) {
        return changed(title, blocks, manualLock, groups);
    }

    Draft withManualLock(boolean enabled) {
        return changed(title, blocks, enabled, groups);
    }

    private Draft changed(String title, List<Block> blocks, boolean enabled, List<Group> groups) {
        return new Draft(id, title, blocks, Math.max(System.currentTimeMillis(), updatedAt + 1), enabled, groups);
    }

    boolean sensitive() {
        return manualLock || containsPrivateBlocks();
    }

    boolean containsPrivateBlocks() {
        return blocks.stream().anyMatch(Block::sensitive);
    }

    record Group(String id, String title, int color, List<String> members, boolean collapsed) {
        Group {
            members = Collections.unmodifiableList(new ArrayList<>(members));
        }
    }

    record Section(int start, int end, Group group) {}

    private Group groupOf(Block block) {
        for (Group group : groups) {
            if (group.members().contains(block.id())) return group;
        }
        return null;
    }

    List<Section> sections() {
        ArrayList<Section> result = new ArrayList<>();
        for (int start = 0; start < blocks.size();) {
            Group group = groupOf(blocks.get(start));
            int end = start + 1;
            while (group != null && end < blocks.size() && group.equals(groupOf(blocks.get(end)))) end++;
            result.add(new Section(start, end, group));
            start = end;
        }
        return result;
    }

    boolean canGroup(Set<String> ids) {
        int count = 0;
        for (Block block : blocks) {
            if (!ids.contains(block.id())) continue;
            if (groupOf(block) != null) return false;
            count++;
        }
        return count >= 2 && count == ids.size();
    }

    Draft group(Set<String> ids, String title, int color) {
        if (!canGroup(ids)) throw new IllegalArgumentException("選取至少兩張未串聯的卡片。");
        ArrayList<Block> selected = new ArrayList<>();
        ArrayList<String> members = new ArrayList<>();
        ArrayList<Block> ordered = new ArrayList<>();
        int insertAt = -1;
        for (Block block : blocks) {
            if (ids.contains(block.id())) {
                if (insertAt == -1) insertAt = ordered.size();
                selected.add(block);
                members.add(block.id());
            } else {
                ordered.add(block);
            }
        }
        ordered.addAll(insertAt, selected);
        ArrayList<Group> next = new ArrayList<>(groups);
        next.add(new Group(UUID.randomUUID().toString(), title, color, members, false));
        return changed(this.title, ordered, manualLock, next);
    }

    Draft updateGroup(Group group, String title, int color) {
        ArrayList<Group> next = new ArrayList<>(groups);
        next.set(next.indexOf(group), new Group(group.id(), title, color, group.members(), group.collapsed()));
        return changed(this.title, blocks, manualLock, next);
    }

    Draft ungroup(Group group) {
        ArrayList<Group> next = new ArrayList<>(groups);
        next.remove(group);
        return changed(title, blocks, manualLock, next);
    }

    Draft toggleGroup(Group group) {
        ArrayList<Group> next = new ArrayList<>(groups);
        next.set(next.indexOf(group), new Group(group.id(), group.title(), group.color(), group.members(),
                !group.collapsed()));
        return new Draft(id, title, blocks, updatedAt, manualLock, next);
    }

    Draft toggleBlock(int index) {
        ArrayList<Block> next = new ArrayList<>(blocks);
        Block block = next.get(index);
        next.set(index, new Block(block.id(), block.sourceId(), block.mime(), block.content(), block.bytes(),
                block.sensitive(), !block.expanded(), block.format()));
        return new Draft(id, title, next, updatedAt, manualLock, groups);
    }

    Draft moveSection(int from, int to) {
        List<Section> ordered = sections();
        ordered.add(to, ordered.remove(from));
        ArrayList<Block> next = new ArrayList<>();
        for (Section section : ordered) next.addAll(blocks.subList(section.start(), section.end()));
        return withContent(title, next);
    }

    Draft moveInside(Section section, int from, int to) {
        int count = section.end() - section.start();
        if (section.group() == null || from < 0 || from >= count || to < 0 || to >= count) {
            throw new IllegalArgumentException("卡片只能在串聯內排序。");
        }
        ArrayList<Block> next = new ArrayList<>(blocks);
        next.add(section.start() + to, next.remove(section.start() + from));
        return withContent(title, next);
    }

    Draft protectSources(Set<String> ids) {
        ArrayList<Block> next = new ArrayList<>(blocks);
        for (int index = 0; index < blocks.size(); index++) {
            Block block = blocks.get(index);
            if (!block.sensitive() && ids.contains(block.sourceId())) next.set(index, block.protect());
        }
        return next.equals(blocks) ? this : new Draft(id, title, next, updatedAt, manualLock, groups);
    }

    JSONObject toJson() throws Exception {
        JSONArray contents = new JSONArray();
        for (Block block : blocks) contents.put(block.toJson());
        JSONArray arrangements = new JSONArray();
        for (Group group : groups) {
            arrangements.put(new JSONObject().put("id", group.id()).put("title", group.title())
                    .put("color", group.color()).put("members", new JSONArray(group.members()))
                    .put("collapsed", group.collapsed()));
        }
        return new JSONObject().put("id", id).put("title", title).put("blocks", contents)
                .put("updatedAt", updatedAt).put("sensitive", sensitive()).put("manualLock", manualLock)
                .put("groups", arrangements);
    }

    static Draft fromJson(JSONObject json) throws Exception {
        JSONArray contents = json.getJSONArray("blocks");
        boolean legacySensitive = json.getBoolean("sensitive");
        ArrayList<Block> blocks = new ArrayList<>(contents.length());
        for (int index = 0; index < contents.length(); index++) {
            blocks.add(Block.fromJson(contents.getJSONObject(index), legacySensitive));
        }
        ArrayList<Group> groups = new ArrayList<>();
        JSONArray arrangements = json.optJSONArray("groups");
        if (arrangements != null) {
            for (int index = 0; index < arrangements.length(); index++) {
                JSONObject group = arrangements.getJSONObject(index);
                JSONArray savedMembers = group.getJSONArray("members");
                ArrayList<String> members = new ArrayList<>();
                for (int member = 0; member < savedMembers.length(); member++) {
                    members.add(savedMembers.getString(member));
                }
                groups.add(new Group(group.getString("id"), group.getString("title"), group.getInt("color"),
                        members, group.optBoolean("collapsed")));
            }
        }
        return new Draft(json.getString("id"), json.getString("title"), blocks,
                json.getLong("updatedAt"), json.optBoolean("manualLock"), groups);
    }

    record Block(String id, String sourceId, String mime, String content, int bytes, boolean sensitive,
                 boolean expanded, TextFormat format) {
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
            return new Block(UUID.randomUUID().toString(), "", mime, content, 0, false, false, TextFormat.PLAIN);
        }

        static Block fromClip(Vault.Entry source) {
            return new Block(UUID.randomUUID().toString(), source.id(), source.mime(), source.content(),
                    source.bytes(), source.sensitive(), false, source.format());
        }

        boolean image() { return mime.startsWith("image/"); }

        Kind kind() {
            for (Kind kind : Kind.values()) {
                if (kind.mime.equals(mime)) return kind;
            }
            return Kind.TEXT;
        }

        Block withContent(String value) {
            return withText(value, format);
        }

        Block withText(String value, TextFormat kind) {
            return new Block(id, sourceId, mime, value, bytes, sensitive, expanded, kind);
        }

        Block protect() {
            return new Block(id, sourceId, mime, content, bytes, true, expanded, format);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("sourceId", sourceId).put("mime", mime)
                    .put("content", content).put("bytes", bytes).put("sensitive", sensitive)
                    .put("expanded", expanded).put("format", format.name());
        }

        static Block fromJson(JSONObject json, boolean legacySensitive) throws Exception {
            String sourceId = json.getString("sourceId");
            // Older protected drafts did not distinguish copied public blocks from private ones.
            boolean sensitive = json.optBoolean("sensitive", legacySensitive && !sourceId.isEmpty());
            String mime = json.getString("mime");
            String content = json.getString("content");
            // Older builds could store a link-only block with no text.
            if (mime.equals(PARAGRAPH) && content.trim().isEmpty()) content = json.optString("url", "");
            TextFormat fallback = sourceId.isEmpty() ? TextFormat.PLAIN : TextFormat.LEGACY;
            return new Block(json.getString("id"), sourceId, mime, content, json.getInt("bytes"),
                    sensitive, json.optBoolean("expanded"), TextFormat.stored(json.optString("format"), fallback));
        }
    }
}
