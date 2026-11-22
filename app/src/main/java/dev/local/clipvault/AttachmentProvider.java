package dev.local.clipvault;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

public final class AttachmentProvider extends ContentProvider {
    private static Handler io;

    static Uri uri(Context context, String id) {
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".attachment")
                .appendPath(id).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        try {
            return Vault.get(getContext()).attachment(id(uri)).mime();
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        try {
            Vault.Entry entry = Vault.get(getContext()).attachment(id(uri));
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = publicName(entry);
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = entry.bytes();
            }
            cursor.addRow(row);
        } catch (Exception ignored) {
            // A removed item has no metadata to return.
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only attachment");
        try {
            return seekable(getContext(), Vault.get(getContext()).attachmentData(id(uri)));
        } catch (Exception ignored) {
            throw new FileNotFoundException("Attachment unavailable");
        }
    }

    static ParcelFileDescriptor seekable(Context context, byte[] bytes) throws IOException {
        StorageManager storage = context.getSystemService(StorageManager.class);
        try {
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                    new ProxyFileDescriptorCallback() {
                        @Override public long onGetSize() { return bytes.length; }

                        @Override public int onRead(long offset, int size, byte[] output) {
                            if (offset >= bytes.length) return 0;
                            int count = Math.min(size, bytes.length - (int) offset);
                            System.arraycopy(bytes, (int) offset, output, 0, count);
                            return count;
                        }

                        @Override public void onRelease() { Arrays.fill(bytes, (byte) 0); }
                    }, handler());
        } catch (IOException | RuntimeException error) {
            Arrays.fill(bytes, (byte) 0);
            throw error;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    private static String publicName(Vault.Entry entry) {
        if (!entry.sensitive()) return entry.content();
        if (entry.mime().equals("application/pdf")) return "attachment.pdf";
        String subtype = entry.mime().substring("image/".length());
        return "attachment." + (subtype.equals("jpeg") ? "jpg" : subtype);
    }

    private static String id(Uri uri) {
        if (uri.getPathSegments().size() != 1) throw new IllegalArgumentException("Invalid URI");
        return UUID.fromString(uri.getLastPathSegment()).toString();
    }

    private static synchronized Handler handler() {
        if (io == null) {
            HandlerThread thread = new HandlerThread("clipvault-attachment");
            thread.start();
            io = new Handler(thread.getLooper());
        }
        return io;
    }
}
