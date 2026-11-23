package dev.local.clipvault;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Vault {
    static final int MAX_TEXT = 20_000;
    static final int MAX_ATTACHMENT = 16 * 1024 * 1024;
    private static final int MAX_ENTRIES = 200;
    private static final int MAX_DRAFTS = 30;
    private static final int MAX_DRAFT_TEXT = 500_000;
    private static final long MAX_MEDIA_TOTAL = 96L * 1024 * 1024;
    private static final String KEY_ALIAS = "clipvault.v1";
    private static final String INDEX_PURPOSE = "index.v1";
    private static final long DAY = 86_400_000L;
    private static Vault instance;

    record Entry(String id, String mime, String content, long savedAt, int bytes,
                 boolean pinned, boolean starred, boolean sensitive, String tag) {
        boolean attachment() { return !mime.equals("text/plain"); }

        String type() {
            if (mime.equals("application/pdf")) return "PDF";
            if (mime.startsWith("image/")) return "圖片";
            return isWebLink(content) ? "連結" : "文字";
        }

        Entry withOptions(boolean pin, boolean star, boolean mask, String label) {
            return new Entry(id, mime, content, savedAt, bytes, pin, star, mask, label);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("id", id).put("mime", mime).put("content", content)
                    .put("savedAt", savedAt).put("bytes", bytes).put("pinned", pinned)
                    .put("starred", starred).put("sensitive", sensitive).put("tag", tag);
        }

        static Entry fromJson(JSONObject json) throws Exception {
            return new Entry(json.getString("id"), json.getString("mime"), json.getString("content"),
                    json.getLong("savedAt"), json.getInt("bytes"), json.getBoolean("pinned"),
                    json.getBoolean("starred"), json.getBoolean("sensitive"), json.getString("tag"));
        }
    }

    static boolean isWebLink(String value) {
        String trimmed = value.trim();
        if (!trimmed.regionMatches(true, 0, "https://", 0, 8)
                && !trimmed.regionMatches(true, 0, "http://", 0, 7)) return false;
        try {
            return URI.create(trimmed).getHost() != null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static final class Limit extends IOException {
        Limit(String message) { super(message); }
    }

    static synchronized Vault get(Context context) {
        if (instance == null) instance = new Vault(context);
        return instance;
    }

    private final File folder;
    private final AtomicFile index;
    private SecretKey key;
    private ArrayList<Entry> entries;
    private List<Draft> drafts;
    private Set<String> reading;
    private int retention;

    private Vault(Context context) {
        folder = new File(context.getNoBackupFilesDir(), "vault");
        index = new AtomicFile(new File(folder, "index.enc"));
    }

    synchronized List<Entry> list() throws Exception {
        load();
        prune();
        ArrayList<Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing(Entry::pinned).reversed()
                .thenComparing(Comparator.comparingLong(Entry::savedAt).reversed()));
        return ordered;
    }

    synchronized int retentionDays() throws Exception {
        load();
        return retention;
    }

    synchronized void setRetention(int days) throws Exception {
        load();
        prune();
        ArrayList<Entry> next = unexpired(days);
        commit(next, days);
    }

    synchronized Entry addText(String text, boolean sensitive) throws Exception {
        validateText(text);
        load();
        prune();
        long now = System.currentTimeMillis();
        ArrayList<Entry> next = new ArrayList<>(entries);
        for (int i = 0; i < next.size(); i++) {
            Entry old = next.get(i);
            if (!old.attachment() && old.content().equals(text)) {
                Entry updated = new Entry(old.id(), old.mime(), text, now, 0, old.pinned(),
                        old.starred(), old.sensitive() || sensitive, old.tag());
                next.set(i, updated);
                commit(next, retention);
                return updated;
            }
        }
        Entry entry = new Entry(UUID.randomUUID().toString(), "text/plain", text, now, 0,
                false, false, sensitive, "");
        insert(next, entry);
        return entry;
    }

    synchronized Entry entry(String id) throws Exception {
        load();
        for (Entry entry : entries) {
            if (entry.id().equals(id) && !expired(entry, retention)) return entry;
        }
        throw new Limit("這筆剪輯已不存在。");
    }

    synchronized void updateText(String id, String text) throws Exception {
        validateText(text);
        Entry current = entry(id);
        if (current.attachment()) throw new Limit("只有文字與連結可以編輯。");
        if (current.content().equals(text)) return;
        update(new Entry(id, current.mime(), text, current.savedAt(), current.bytes(),
                current.pinned(), current.starred(), current.sensitive(), current.tag()));
    }

    private static void validateText(String text) throws Limit {
        if (text.trim().isEmpty()) throw new Limit("沒有可儲存的文字。");
        if (text.length() > MAX_TEXT) throw new Limit("文字超過 20,000 個字元。");
    }

    synchronized Entry addAttachment(String mime, String name, byte[] data, boolean sensitive)
            throws Exception {
        if (!supportsAttachment(mime) || data.length == 0 || data.length > MAX_ATTACHMENT) {
            throw new Limit("請選擇 16 MiB 以內的圖片。");
        }
        load();
        prune();
        ArrayList<Entry> next = new ArrayList<>(entries);
        long used = storedAttachments(next, drafts).values().stream().mapToLong(Integer::longValue).sum();
        if (used + data.length > MAX_MEDIA_TOTAL) throw new Limit("附件空間已滿（96 MiB）。");
        Entry entry = new Entry(UUID.randomUUID().toString(), mime, name, System.currentTimeMillis(),
                data.length, false, false, sensitive, "");
        File encrypted = file(entry.id());
        write(new AtomicFile(encrypted), encrypt(data, entry.id()));
        try {
            insert(next, entry);
        } catch (Exception error) {
            new AtomicFile(encrypted).delete();
            throw error;
        }
        return entry;
    }

    synchronized void update(Entry entry) throws Exception {
        load();
        prune();
        ArrayList<Entry> next = new ArrayList<>(entries);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id().equals(entry.id())) {
                next.set(i, entry);
                commit(next, retention);
                return;
            }
        }
        throw new Limit("這筆剪輯已不存在。");
    }

    synchronized void delete(String id) throws Exception {
        load();
        ArrayList<Entry> next = unexpired(retention);
        next.removeIf(entry -> entry.id().equals(id));
        commit(next, retention);
    }

    synchronized void clear() throws Exception {
        load();
        commit(new ArrayList<>(), retention);
    }

    synchronized Entry attachment(String id) throws Exception {
        load();
        for (Entry entry : entries) {
            if (entry.id().equals(id) && entry.attachment() && !expired(entry, retention)) {
                return entry;
            }
        }
        throw new IOException("Attachment unavailable");
    }

    synchronized byte[] attachmentData(String id) throws Exception {
        attachment(id);
        return decrypt(new AtomicFile(file(id)).readFully(), id);
    }

    synchronized Set<String> reading() throws Exception {
        load();
        return new HashSet<>(reading);
    }

    synchronized void setReading(String id, boolean waiting) throws Exception {
        Entry entry = entry(id);
        if (!entry.type().equals("連結")) throw new Limit("待讀清單適用於網址。");
        HashSet<String> next = new HashSet<>(reading);
        if (waiting) next.add(id);
        else next.remove(id);
        commit(entries, retention, drafts, next);
    }

    synchronized List<Draft> drafts() throws Exception {
        load();
        ArrayList<Draft> ordered = new ArrayList<>(drafts);
        ordered.sort(Comparator.comparingLong(Draft::updatedAt).reversed());
        return ordered;
    }

    synchronized Draft draft(String id) throws Exception {
        load();
        for (Draft draft : drafts) {
            if (draft.id().equals(id)) return draft;
        }
        throw new Limit("這篇草稿已不存在。");
    }

    synchronized Draft createDraft(String title) throws Exception {
        load();
        if (drafts.size() >= MAX_DRAFTS) throw new Limit("草稿已達 30 篇。");
        Draft draft = Draft.create(title);
        ArrayList<Draft> next = new ArrayList<>(drafts);
        next.add(draft);
        commit(entries, retention, next);
        return draft;
    }

    synchronized Draft updateDraft(Draft original, Draft changed) throws Exception {
        Draft current = draft(original.id());
        if (!current.equals(original)) throw new Limit("草稿已有變更，請重新開啟。");
        if (changed.blocks().size() > Draft.MAX_BLOCKS) throw new Limit("每篇草稿最多 50 個區塊。");
        int size = changed.blocks().stream().mapToInt(block -> block.content().length()).sum();
        for (Draft draft : drafts) {
            if (!draft.id().equals(current.id())) {
                size += draft.blocks().stream().mapToInt(block -> block.content().length()).sum();
            }
        }
        if (size > MAX_DRAFT_TEXT) throw new Limit("草稿總文字量已達上限。");
        ArrayList<Draft> next = new ArrayList<>(drafts);
        next.set(next.indexOf(current), current.sensitive() ? changed.protect() : changed);
        commit(entries, retention, next);
        return draft(current.id());
    }

    synchronized Draft addDraftClip(String draftId, String entryId, boolean authenticated) throws Exception {
        Draft current = draft(draftId);
        Entry source = entry(entryId);
        if (source.attachment() && !supportsAttachment(source.mime())) {
            throw new Limit("草稿支援文字、網址與圖片。");
        }
        if ((current.sensitive() || source.sensitive()) && !authenticated) {
            throw new Limit("請先驗證私密內容。");
        }
        ArrayList<Draft.Block> blocks = new ArrayList<>(current.blocks());
        blocks.add(Draft.Block.fromClip(source));
        Draft changed = current.withContent(current.title(), blocks);
        return updateDraft(current, source.sensitive() ? changed.protect() : changed);
    }

    synchronized void deleteDraft(Draft confirmed) throws Exception {
        Draft current = draft(confirmed.id());
        if (!current.equals(confirmed)) throw new Limit("草稿已有變更，請重新確認刪除。");
        ArrayList<Draft> next = new ArrayList<>(drafts);
        next.remove(current);
        commit(entries, retention, next);
    }

    synchronized byte[] draftImage(Draft draft, Draft.Block block) throws Exception {
        if (!draft(draft.id()).equals(draft) || !block.image() || !draft.blocks().contains(block)) {
            throw new Limit("草稿圖片已變更，請重新開啟。");
        }
        return decrypt(new AtomicFile(file(block.sourceId())).readFully(), block.sourceId());
    }

    static boolean supportsAttachment(String mime) {
        return mime != null && mime.matches("image/[a-z0-9.+_-]+");
    }

    private void insert(ArrayList<Entry> next, Entry entry) throws Exception {
        if (next.size() >= MAX_ENTRIES) throw new Limit("剪輯已達 200 筆，請先刪除幾筆。");
        next.add(entry);
        commit(next, retention);
    }

    private void load() throws Exception {
        if (entries != null) return;
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Vault unavailable");
        ArrayList<Entry> loaded = new ArrayList<>();
        ArrayList<Draft> loadedDrafts = new ArrayList<>();
        HashSet<String> loadedReading = new HashSet<>();
        int days = 30;
        byte[] encrypted = null;
        try {
            encrypted = index.readFully();
        } catch (FileNotFoundException error) {
            if (index.getBaseFile().exists()) throw error;
        }
        if (encrypted != null) {
            byte[] plain = decrypt(encrypted, INDEX_PURPOSE);
            try {
                JSONObject state = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                days = state.getInt("retention");
                JSONArray data = state.getJSONArray("entries");
                for (int i = 0; i < data.length(); i++) loaded.add(Entry.fromJson(data.getJSONObject(i)));
                JSONArray readingData = state.optJSONArray("reading");
                if (readingData != null) {
                    for (int i = 0; i < readingData.length(); i++) {
                        loadedReading.add(readingData.getString(i));
                    }
                }
                JSONArray draftData = state.optJSONArray("drafts");
                if (draftData != null) {
                    for (int i = 0; i < draftData.length(); i++) {
                        loadedDrafts.add(Draft.fromJson(draftData.getJSONObject(i)));
                    }
                }
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        }
        entries = loaded;
        drafts = loadedDrafts;
        reading = loadedReading;
        retention = days;
        removeOrphanFiles();
    }

    private boolean expired(Entry entry, int days) {
        return days > 0 && !entry.pinned() && !reading.contains(entry.id()) && entry.savedAt() <= System.currentTimeMillis() - days * DAY;
    }

    private ArrayList<Entry> unexpired(int days) {
        ArrayList<Entry> next = new ArrayList<>(entries);
        next.removeIf(entry -> expired(entry, days));
        return next;
    }

    private void prune() throws Exception {
        ArrayList<Entry> next = unexpired(retention);
        if (next.size() != entries.size()) commit(next, retention);
    }

    private void commit(ArrayList<Entry> next, int days) throws Exception {
        commit(next, days, drafts, reading);
    }

    private void commit(ArrayList<Entry> next, int days, List<Draft> nextDrafts) throws Exception {
        commit(next, days, nextDrafts, reading);
    }

    private void commit(ArrayList<Entry> next, int days, List<Draft> nextDrafts,
                        Set<String> nextReading) throws Exception {
        JSONArray data = new JSONArray();
        JSONArray readingData = new JSONArray();
        HashSet<String> keptReading = new HashSet<>();
        HashSet<String> sensitiveIds = new HashSet<>();
        for (Entry entry : next) {
            data.put(entry.toJson());
            if (entry.sensitive()) sensitiveIds.add(entry.id());
            if (nextReading.contains(entry.id()) && entry.type().equals("連結")) {
                keptReading.add(entry.id());
                readingData.put(entry.id());
            }
        }
        JSONArray draftData = new JSONArray();
        ArrayList<Draft> protectedDrafts = new ArrayList<>(nextDrafts.size());
        for (Draft draft : nextDrafts) {
            boolean sensitiveSource = draft.blocks().stream().anyMatch(block -> sensitiveIds.contains(block.sourceId()));
            Draft saved = sensitiveSource ? draft.protect() : draft;
            protectedDrafts.add(saved);
            draftData.put(saved.toJson());
        }
        byte[] plain = new JSONObject().put("entries", data).put("retention", days).put("drafts", draftData).put("reading", readingData)
                .toString().getBytes(StandardCharsets.UTF_8);
        try {
            write(index, encrypt(plain, INDEX_PURPOSE));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        entries = next;
        drafts = protectedDrafts;
        reading = keptReading;
        retention = days;
        removeOrphanFiles();
    }

    private static HashMap<String, Integer> storedAttachments(List<Entry> entries, List<Draft> drafts) {
        HashMap<String, Integer> retained = new HashMap<>();
        for (Entry entry : entries) {
            if (entry.attachment()) retained.put(entry.id(), entry.bytes());
        }
        for (Draft draft : drafts) {
            for (Draft.Block block : draft.blocks()) {
                if (block.image()) retained.put(block.sourceId(), block.bytes());
            }
        }
        return retained;
    }

    private void removeOrphanFiles() {
        File[] blobs = folder.listFiles((dir, name) -> name.endsWith(".blob")
                || name.endsWith(".blob.new") || name.endsWith(".blob.bak"));
        if (blobs == null || blobs.length == 0) return;
        HashMap<String, Integer> retained = storedAttachments(entries, drafts);
        for (File blob : blobs) {
            String id = blob.getName().substring(0, blob.getName().indexOf('.'));
            if (!retained.containsKey(id)) blob.delete();
        }
    }

    private File file(String id) {
        return new File(folder, id + ".blob");
    }

    private SecretKey key() throws Exception {
        if (key != null) return key;
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key == null) {
            if (index.getBaseFile().exists()) throw new IOException("Vault encryption key unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build());
            key = generator.generateKey();
        }
        return key;
    }

    private byte[] encrypt(byte[] plain, String purpose) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        byte[] nonce = cipher.getIV();
        if (nonce.length != 12) throw new IOException("Unexpected encryption nonce length");
        byte[] encrypted = cipher.doFinal(plain);
        byte[] combined = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
        return combined;
    }

    private byte[] decrypt(byte[] combined, String purpose) throws Exception {
        if (combined.length < 28) throw new IOException("Invalid encrypted data");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, combined, 0, 12));
        cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(combined, 12, combined.length - 12);
    }

    private static void write(AtomicFile target, byte[] bytes) throws IOException {
        FileOutputStream output = target.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
            target.finishWrite(output);
        } catch (IOException error) {
            target.failWrite(output);
            throw error;
        }
    }
}
