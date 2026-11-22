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
import java.util.List;
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
            String trimmed = content.trim();
            if (trimmed.regionMatches(true, 0, "https://", 0, 8)
                    || trimmed.regionMatches(true, 0, "http://", 0, 7)) {
                try {
                    if (URI.create(trimmed).getHost() != null) return "連結";
                } catch (IllegalArgumentException ignored) {
                    // Ordinary text does not need to be a valid URI.
                }
            }
            return "文字";
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
        if (text.trim().isEmpty()) throw new Limit("沒有可儲存的文字。");
        if (text.length() > MAX_TEXT) throw new Limit("文字超過 20,000 個字元。");
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

    synchronized Entry addAttachment(String mime, String name, byte[] data, boolean sensitive)
            throws Exception {
        if (!supportsAttachment(mime) || data.length == 0 || data.length > MAX_ATTACHMENT) {
            throw new Limit("請選擇 16 MB 以內的圖片或 PDF。");
        }
        load();
        prune();
        ArrayList<Entry> next = new ArrayList<>(entries);
        long used = next.stream().mapToLong(Entry::bytes).sum();
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

    static boolean supportsAttachment(String mime) {
        return mime != null && (mime.equals("application/pdf") || mime.matches("image/[a-z0-9.+_-]+"));
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
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        }
        entries = loaded;
        retention = days;
        removeOrphanFiles();
    }

    private boolean expired(Entry entry, int days) {
        return days > 0 && !entry.pinned() && entry.savedAt() <= System.currentTimeMillis() - days * DAY;
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
        JSONArray data = new JSONArray();
        for (Entry entry : next) data.put(entry.toJson());
        byte[] plain = new JSONObject().put("entries", data).put("retention", days)
                .toString().getBytes(StandardCharsets.UTF_8);
        try {
            write(index, encrypt(plain, INDEX_PURPOSE));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        entries = next;
        retention = days;
        removeOrphanFiles();
    }

    private void removeOrphanFiles() {
        File[] blobs = folder.listFiles((dir, name) -> name.endsWith(".blob")
                || name.endsWith(".blob.new") || name.endsWith(".blob.bak"));
        if (blobs == null) return;
        for (File blob : blobs) {
            String id = blob.getName().substring(0, blob.getName().indexOf('.'));
            if (entries.stream().noneMatch(entry -> entry.attachment() && entry.id().equals(id))) {
                blob.delete();
            }
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
