package dev.local.clipvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 10;
    private static final int UNLOCK = 11;
    private static final int ENABLE_LOCK = 12;
    private static final int BG = 0xfff3f5ee;
    private static final int INK = 0xff132b23;
    private static final int MUTED = 0xff486258;
    private static final int FOREST = 0xff184735;
    private static final int SOFT = 0xffe5ede1;
    private static final int LIME = 0xffd7f0b0;
    private static final int WHITE = 0xffffffff;
    private static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final String[] FILTERS = {"全部", "收藏", "文字", "連結", "圖片", "PDF"};
    private static final int[] RETENTION = {7, 30, 90, 0};
    private static final String[] RETENTION_NAMES = {"7 天", "30 天", "90 天", "永久保留"};
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private final ArrayList<Button> filters = new ArrayList<>();
    private Vault vault;
    private ClipboardManager clipboard;
    private SharedPreferences preferences;
    private LinearLayout root;
    private LinearLayout results;
    private TextView listTitle;
    private TextView listCount;
    private List<Vault.Entry> entries = Collections.emptyList();
    private AlertDialog dialog;
    private boolean locked;
    private boolean resumed;
    private boolean busy;
    private boolean loadFailed;
    private boolean loaded;
    private int filter;
    private int retentionDays = 30;
    private int screenVersion;
    private String search = "";
    private Intent pendingShare;
    private Uri pendingFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        vault = Vault.get(this);
        clipboard = getSystemService(ClipboardManager.class);
        preferences = getSharedPreferences("privacy", MODE_PRIVATE);
        locked = preferences.getBoolean("lock", false);
        if (state == null && Intent.ACTION_SEND.equals(getIntent().getAction())) pendingShare = getIntent();
        if (locked) showLocked();
        else showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!locked) refresh();
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onStop() {
        if (preferences.getBoolean("lock", false) && !locked) {
            locked = true;
            entries = Collections.emptyList();
            loaded = false;
            search = "";
            showLocked();
        } else {
            dismissDialog();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        dismissDialog();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            pendingShare = intent;
            consumeImport();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) return;
        if (request == PICK_FILE && data != null && data.getData() != null) {
            pendingFile = data.getData();
            consumeImport();
        } else if (request == UNLOCK) {
            locked = false;
            showHome();
            if (resumed) refresh();
        } else if (request == ENABLE_LOCK) {
            preferences.edit().putBoolean("lock", true).apply();
            toast("已啟用離開 App 時上鎖。");
        }
    }

    private void showHome() {
        startScreen();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = column();
        page.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = row();
        LinearLayout identity = column();
        identity.addView(text("剪存", 34, INK, true));
        identity.addView(text("本機剪貼簿", 14, MUTED, false));
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button("隱私設定", SOFT, FOREST, this::settings));
        page.addView(header, margins(-1, -2, 0, 18));

        LinearLayout hero = column();
        hero.setBackground(shape(FOREST, 24));
        hero.setPadding(dp(16), dp(16), dp(16), dp(16));
        hero.addView(text("收錄剪輯", 22, WHITE, true));
        hero.addView(text("貼上文字或連結，也可匯入圖片與 PDF。", 14, 0xffe0ece2, false),
                margins(-1, -2, 5, 12));
        hero.addView(button("儲存目前剪貼簿", LIME, INK, this::captureClipboard),
                new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout actions = row();
        actions.addView(button("新增文字", 0xff30604f, WHITE, this::addText),
                new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(48), 1);
        right.leftMargin = dp(8);
        actions.addView(button("匯入檔案", 0xff30604f, WHITE, this::pickFile), right);
        hero.addView(actions, margins(-1, -2, 8, 0));
        page.addView(hero);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setHint("搜尋內容、檔名或標籤");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(shape(WHITE, 15));
        input.setText(search);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                search = text.toString();
                render();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        page.addView(input, margins(-1, dp(50), 16, 7));

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = row();
        filters.clear();
        for (int i = 0; i < FILTERS.length; i++) {
            int selected = i;
            Button chip = button(FILTERS[i], SOFT, FOREST, () -> {
                filter = selected;
                render();
            });
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, dp(48));
            layout.rightMargin = dp(6);
            chips.addView(chip, layout);
            filters.add(chip);
        }
        strip.addView(chips);
        page.addView(strip, margins(-1, -2, 0, 12));

        LinearLayout listHeader = row();
        listTitle = text("剪輯", 22, INK, true);
        listHeader.addView(listTitle, new LinearLayout.LayoutParams(0, -2, 1));
        listCount = text("", 14, MUTED, false);
        listHeader.addView(listCount);
        page.addView(listHeader, margins(-1, -2, 0, 8));
        results = column();
        page.addView(results);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        render();
    }

    private void showLocked() {
        startScreen();
        results = null;
        LinearLayout gate = column();
        gate.setGravity(Gravity.CENTER);
        gate.setPadding(dp(24), dp(24), dp(24), dp(24));
        gate.addView(text("剪存已上鎖", 30, INK, true));
        gate.addView(text("使用手機的螢幕鎖定憑證解鎖。", 15, MUTED, false),
                margins(-2, -2, 8, 16));
        gate.addView(button("解鎖", FOREST, WHITE, () -> requestCredential(UNLOCK)),
                new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(gate, new LinearLayout.LayoutParams(-1, -1));
    }

    private void startScreen() {
        dismissDialog();
        screenVersion++;
        busy = false;
        root = column();
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets system;
            if (Build.VERSION.SDK_INT >= 30) {
                system = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            } else {
                system = android.graphics.Insets.of(insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            view.setPadding(system.left, system.top, system.right, system.bottom);
            return insets;
        });
        setContentView(root);
    }

    private void render() {
        if (results == null) return;
        for (int i = 0; i < filters.size(); i++) {
            Button chip = filters.get(i);
            chip.setTextColor(i == filter ? WHITE : FOREST);
            chip.setBackground(buttonBackground(i == filter ? FOREST : SOFT, 18));
            chip.setSelected(i == filter);
        }
        listTitle.setText(filter == 0 ? "剪輯" : FILTERS[filter]);
        results.removeAllViews();
        if (!loaded) {
            listCount.setText("");
            if (loadFailed) {
                results.addView(text("無法開啟本機資料庫。", 15, MUTED, false));
                results.addView(button("重試", SOFT, FOREST, this::refresh), margins(-2, -2, 8, 0));
            } else {
                results.addView(text("讀取中…", 15, MUTED, false));
            }
            return;
        }

        String query = search.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (Vault.Entry entry : entries) {
            if (filter == 1 && !entry.starred()) continue;
            if (filter >= 2 && !FILTERS[filter].equals(entry.type())) continue;
            if (!query.isEmpty() && (entry.sensitive()
                    || (!entry.content().toLowerCase(Locale.ROOT).contains(query)
                    && !entry.tag().toLowerCase(Locale.ROOT).contains(query)))) continue;
            count++;
            results.addView(card(entry), margins(-1, -2, 0, 8));
        }
        listCount.setText(count + " 筆");
        if (count == 0) {
            TextView message = text(entries.isEmpty() ? "你的剪輯會顯示在這裡。" : "這個篩選條件沒有結果。",
                    15, MUTED, false);
            message.setPadding(dp(16), dp(18), dp(16), dp(18));
            message.setBackground(shape(WHITE, 17));
            results.addView(message, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private LinearLayout card(Vault.Entry entry) {
        LinearLayout card = column();
        card.setBackground(shape(WHITE, 17));
        card.setPadding(dp(14), dp(12), dp(14), dp(8));
        String badge = entry.type();
        if (entry.pinned()) badge += "  ·  置頂";
        if (entry.starred()) badge += "  ·  收藏";
        if (!entry.sensitive() && !entry.tag().isEmpty()) badge += "  ·  " + entry.tag();
        card.addView(text(badge, 13, FOREST, true));
        TextView content = text(entry.sensitive() ? "敏感剪輯" : entry.content(), 16, INK, true);
        content.setMaxLines(2);
        content.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(content, margins(-1, -2, 5, 2));
        card.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(entry.savedAt())), 12, MUTED, false));

        LinearLayout actions = row();
        actions.addView(button("複製", SOFT, FOREST, () -> copy(entry)));
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(-2, dp(48));
        spacing.leftMargin = dp(6);
        actions.addView(button("查看", SOFT, FOREST, () -> preview(entry)), spacing);
        LinearLayout.LayoutParams more = new LinearLayout.LayoutParams(-2, dp(48));
        more.leftMargin = dp(6);
        actions.addView(button("更多", WHITE, FOREST, () -> entryOptions(entry)), more);
        card.addView(actions, margins(-1, -2, 8, 0));
        return card;
    }

    private void refresh() {
        if (busy || locked) return;
        run(() -> new Snapshot(vault.list(), vault.retentionDays()), snapshot -> {
            loadFailed = false;
            loaded = true;
            entries = snapshot.entries();
            retentionDays = snapshot.days();
            render();
            consumeImport();
        }, () -> {
            loadFailed = true;
            render();
        });
    }

    private record Snapshot(List<Vault.Entry> entries, int days) {}
    private record Capture(String text, Uri uri, String mime, boolean sensitive) {}

    private void captureClipboard() {
        if (busy || !hasWindowFocus()) return;
        try {
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                toast("目前沒有可讀取的剪貼簿內容。");
                return;
            }
            ClipData.Item item = clip.getItemAt(0);
            ClipDescription description = clip.getDescription();
            PersistableBundle extras = description.getExtras();
            boolean sensitive = extras != null && extras.getBoolean(SENSITIVE);
            CharSequence value = item.getText();
            if (value != null && value.length() > 0) {
                offer(new Capture(value.toString(), null, null, sensitive));
            } else if (isContent(item.getUri())) {
                String mime = null;
                for (int i = 0; i < description.getMimeTypeCount(); i++) {
                    String candidate = description.getMimeType(i);
                    if (Vault.supportsAttachment(candidate)) {
                        mime = candidate;
                        break;
                    }
                }
                offer(new Capture(null, item.getUri(), mime, sensitive));
            } else {
                toast("支援純文字、圖片與 PDF。");
            }
        } catch (RuntimeException error) {
            toast("無法讀取目前的剪貼簿。");
        }
    }

    private void addText() {
        if (busy) return;
        LinearLayout body = dialogBody();
        EditText input = edit("輸入文字或連結", Vault.MAX_TEXT, true);
        body.addView(input);
        CheckBox mask = checkbox("標為敏感", false);
        body.addView(mask);
        AlertDialog prompt = show(new AlertDialog.Builder(this).setTitle("新增文字").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("儲存", null));
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText().toString();
            if (value.trim().isEmpty()) {
                input.setError("請輸入文字");
                return;
            }
            prompt.dismiss();
            save(new Capture(value, null, null, mask.isChecked()), mask.isChecked());
        });
    }

    private void pickFile() {
        if (busy) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        try {
            startActivityForResult(picker, PICK_FILE);
        } catch (RuntimeException error) {
            toast("裝置上沒有可用的檔案選擇器。");
        }
    }

    private void consumeImport() {
        if (locked || !loaded || !resumed || busy) return;
        if (pendingFile != null) {
            Uri uri = pendingFile;
            pendingFile = null;
            if (isContent(uri)) offer(new Capture(null, uri, null, false));
            else toast("無法存取選取的檔案。");
            return;
        }
        if (pendingShare == null) return;
        Intent share = pendingShare;
        pendingShare = null;
        try {
            ClipData sharedClip = share.getClipData();
            PersistableBundle extras = sharedClip == null ? null : sharedClip.getDescription().getExtras();
            boolean sensitive = extras != null && extras.getBoolean(SENSITIVE);
            String mime = share.getType();
            if ("text/plain".equals(mime)) {
                CharSequence value = share.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (value != null) {
                    offer(new Capture(value.toString(), null, null, sensitive));
                    return;
                }
            } else if (Vault.supportsAttachment(mime)) {
                Object stream = share.getParcelableExtra(Intent.EXTRA_STREAM);
                Uri uri = stream instanceof Uri ? (Uri) stream : null;
                if (uri == null && sharedClip != null && sharedClip.getItemCount() == 1) {
                    uri = sharedClip.getItemAt(0).getUri();
                }
                if (isContent(uri)) {
                    offer(new Capture(null, uri, mime, sensitive));
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed share extras are treated as an unsupported import.
        }
        toast("無法收錄分享內容。支援純文字、圖片與 PDF。");
    }

    private void offer(Capture capture) {
        if (capture.text() != null && capture.text().length() > Vault.MAX_TEXT) {
            toast("文字超過 20,000 個字元。");
            return;
        }
        LinearLayout body = dialogBody();
        String message = capture.uri() != null ? "將附件複製到本機加密資料庫。"
                : "將這段文字保存到本機加密資料庫。";
        body.addView(text(message, 16, INK, false));
        CheckBox mask = checkbox("標為敏感", capture.sensitive());
        mask.setEnabled(!capture.sensitive());
        body.addView(mask);
        show(new AlertDialog.Builder(this).setTitle("收錄剪輯").setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", (d, which) -> save(capture, mask.isChecked())));
    }

    private void save(Capture capture, boolean sensitive) {
        mutate(() -> {
            if (capture.uri() == null) vault.addText(capture.text(), sensitive);
            else importAttachment(capture.uri(), capture.mime(), sensitive);
            return null;
        }, "已儲存");
    }

    private void importAttachment(Uri uri, String hint, boolean sensitive) throws Exception {
        if (!isContent(uri)) throw new Vault.Limit("無法讀取此附件。");
        String mime = getContentResolver().getType(uri);
        if (!Vault.supportsAttachment(mime)) mime = hint;
        if (!Vault.supportsAttachment(mime)) throw new Vault.Limit("僅支援圖片和 PDF。");

        String name = mime.equals("application/pdf") ? "文件.pdf" : "圖片";
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                name = cursor.getString(0).replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
                if (name.length() > 180) name = name.substring(0, 180);
                if (name.isEmpty()) name = "附件";
            }
        } catch (RuntimeException ignored) {
            // Some providers support streaming without exposing a display name.
        }
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("Attachment unavailable");
            byte[] data = readAttachment(input);
            try {
                vault.addAttachment(mime, name, data, sensitive);
            } finally {
                Arrays.fill(data, (byte) 0);
            }
        }
    }

    private static byte[] readAttachment(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int length; (length = input.read(buffer)) != -1;) {
            if ((long) bytes.size() + length > Vault.MAX_ATTACHMENT) {
                throw new Vault.Limit("附件超過 16 MiB。");
            }
            bytes.write(buffer, 0, length);
        }
        return bytes.toByteArray();
    }

    private static boolean isContent(Uri uri) {
        return uri != null && "content".equals(uri.getScheme()) && uri.getAuthority() != null;
    }

    private void copy(Vault.Entry entry) {
        if (busy) return;
        Runnable action = () -> {
            try {
                ClipData clip = entry.attachment()
                        ? new ClipData("剪存附件", new String[]{entry.mime()},
                                new ClipData.Item(AttachmentProvider.uri(this, entry.id())))
                        : ClipData.newPlainText("剪存", entry.content());
                PersistableBundle options = new PersistableBundle();
                options.putBoolean(SENSITIVE, true);
                clip.getDescription().setExtras(options);
                clipboard.setPrimaryClip(clip);
                if (Build.VERSION.SDK_INT < 33) toast("已複製到系統剪貼簿。");
            } catch (RuntimeException error) {
                toast("無法複製這筆剪輯。");
            }
        };
        if (entry.sensitive()) confirm("複製敏感剪輯", "系統剪貼簿中的內容可貼到其他 App。", "複製", action);
        else action.run();
    }

    private void preview(Vault.Entry entry) {
        if (busy) return;
        if (entry.sensitive()) {
            confirm("顯示敏感剪輯", "顯示這筆剪輯的實際內容。", "顯示", () -> openPreview(entry));
        } else {
            openPreview(entry);
        }
    }

    private void openPreview(Vault.Entry entry) {
        if (!entry.attachment()) {
            ScrollView scroll = new ScrollView(this);
            scroll.setPadding(dp(22), dp(4), dp(22), dp(12));
            scroll.addView(text(entry.content(), 16, INK, false));
            show(new AlertDialog.Builder(this).setTitle(entry.type()).setView(scroll)
                    .setPositiveButton("關閉", null));
            return;
        }
        run(() -> previewBitmap(entry), bitmap -> {
            if (!resumed) return;
            LinearLayout body = dialogBody();
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(bitmap);
            image.setContentDescription(entry.type() + "預覽");
            body.addView(image, new LinearLayout.LayoutParams(-1, -2));
            if (entry.mime().equals("application/pdf")) body.addView(text("第 1 頁", 13, MUTED, false));
            show(new AlertDialog.Builder(this).setTitle(entry.sensitive() ? "預覽" : entry.content())
                    .setView(body).setPositiveButton("關閉", null));
        }, null);
    }

    private Bitmap previewBitmap(Vault.Entry entry) throws Exception {
        byte[] bytes = vault.attachmentData(entry.id());
        if (entry.mime().equals("application/pdf")) {
            try (ParcelFileDescriptor fd = AttachmentProvider.seekable(this, bytes);
                 PdfRenderer renderer = new PdfRenderer(fd);
                 PdfRenderer.Page page = renderer.openPage(0)) {
                double factor = 900.0 / Math.max(page.getWidth(), page.getHeight());
                int width = Math.max(1, (int) (page.getWidth() * factor));
                int height = Math.max(1, (int) (page.getHeight() * factor));
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        }
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1100) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (bitmap == null) throw new Vault.Limit("無法預覽這個圖片格式。");
            return bitmap;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void entryOptions(Vault.Entry entry) {
        if (busy) return;
        show(new AlertDialog.Builder(this).setTitle("剪輯選項")
                .setItems(new String[]{"置頂、收藏與標籤", "分享", "刪除"}, (d, which) -> {
                    if (which == 0) editOptions(entry);
                    if (which == 1) share(entry);
                    if (which == 2) confirm("刪除剪輯", "這筆剪輯及其附件會從剪存移除。", "刪除",
                            () -> mutate(() -> { vault.delete(entry.id()); return null; }, "已刪除"));
                }));
    }

    private void editOptions(Vault.Entry entry) {
        LinearLayout body = dialogBody();
        CheckBox pin = checkbox("置頂並保留到手動刪除", entry.pinned());
        CheckBox star = checkbox("加入收藏", entry.starred());
        CheckBox mask = checkbox("標為敏感", entry.sensitive());
        EditText tag = edit("自訂標籤", 40, false);
        tag.setText(entry.tag());
        body.addView(pin);
        body.addView(star);
        body.addView(mask);
        body.addView(tag);
        show(new AlertDialog.Builder(this).setTitle("整理剪輯").setView(body)
                .setNegativeButton("取消", null).setPositiveButton("儲存", (d, which) -> mutate(() -> {
                    vault.update(entry.withOptions(pin.isChecked(), star.isChecked(), mask.isChecked(),
                            tag.getText().toString().trim()));
                    return null;
                }, "已更新")));
    }

    private void share(Vault.Entry entry) {
        Runnable action = () -> {
            Intent send = new Intent(Intent.ACTION_SEND).setType(entry.mime());
            if (entry.attachment()) {
                Uri uri = AttachmentProvider.uri(this, entry.id());
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.setClipData(new ClipData("剪存附件", new String[]{entry.mime()}, new ClipData.Item(uri)));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                send.putExtra(Intent.EXTRA_TEXT, entry.content());
            }
            try {
                startActivity(Intent.createChooser(send, "分享剪輯"));
            } catch (RuntimeException error) {
                toast("沒有可用的分享目標。");
            }
        };
        if (entry.sensitive()) confirm("分享敏感剪輯", "選取的 App 將收到剪輯原始內容。", "繼續", action);
        else action.run();
    }

    private void settings() {
        if (busy) return;
        boolean enabled = preferences.getBoolean("lock", false);
        String days = retentionDays == 0 ? "永久保留" : retentionDays + " 天";
        show(new AlertDialog.Builder(this).setTitle("隱私設定")
                .setItems(new String[]{"保留期限 · " + days,
                        "離開時上鎖 · " + (enabled ? "開啟" : "關閉"), "刪除所有剪輯"}, (d, which) -> {
                    if (which == 0) retention();
                    if (which == 1 && !enabled) requestCredential(ENABLE_LOCK);
                    if (which == 1 && enabled) confirm("關閉離開時上鎖",
                            "之後開啟剪存時會直接顯示資料庫。", "關閉", () -> {
                                preferences.edit().putBoolean("lock", false).apply();
                                toast("已關閉離開時上鎖。");
                            });
                    if (which == 2) confirm("刪除所有剪輯", "文字與附件將從剪存移除，包含置頂與收藏。", "全部刪除",
                            () -> mutate(() -> { vault.clear(); return null; }, "已清除剪存的資料"));
                })
                .setNegativeButton("關閉", null));
    }

    private void retention() {
        int checked = 0;
        for (int i = 0; i < RETENTION.length; i++) if (RETENTION[i] == retentionDays) checked = i;
        show(new AlertDialog.Builder(this).setTitle("保留期限").setSingleChoiceItems(RETENTION_NAMES,
                checked, (dialog, which) -> {
                    dialog.dismiss();
                    int days = RETENTION[which];
                    if (days == retentionDays) return;
                    if (days > 0 && (retentionDays == 0 || days < retentionDays)) {
                        confirm("縮短保留期限", "超過期限的未置頂剪輯將立即刪除。", "套用",
                                () -> saveRetention(days));
                    } else {
                        saveRetention(days);
                    }
                }).setNegativeButton("取消", null));
    }

    private void saveRetention(int days) {
        mutate(() -> { vault.setRetention(days); return null; }, "已更新保留期限");
    }

    private void requestCredential(int request) {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        Intent credential = keyguard.createConfirmDeviceCredentialIntent("剪存", "驗證裝置鎖定憑證");
        if (credential == null) {
            toast("請先在 Android 系統設定中啟用螢幕鎖定。");
            return;
        }
        startActivityForResult(credential, request);
    }

    private void mutate(Callable<Void> action, String success) {
        run(() -> {
            action.call();
            return new Snapshot(vault.list(), vault.retentionDays());
        }, snapshot -> {
            entries = snapshot.entries();
            retentionDays = snapshot.days();
            loaded = true;
            render();
            toast(success);
            consumeImport();
        }, null);
    }

    private <T> void run(Callable<T> operation, Consumer<T> done, Runnable failure) {
        if (busy || locked) return;
        busy = true;
        int version = screenVersion;
        WORK.execute(() -> {
            try {
                T result = operation.call();
                runOnUiThread(() -> {
                    if (version != screenVersion || isDestroyed()) return;
                    busy = false;
                    done.accept(result);
                });
            } catch (Exception error) {
                String message = error instanceof Vault.Limit ? error.getMessage() : "操作失敗，請重試。";
                runOnUiThread(() -> {
                    if (version != screenVersion || isDestroyed()) return;
                    busy = false;
                    toast(message);
                    if (failure != null) failure.run();
                });
            }
        });
    }

    private void confirm(String title, String message, String action, Runnable task) {
        show(new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton(action, (d, which) -> task.run()));
    }

    private AlertDialog show(AlertDialog.Builder builder) {
        dismissDialog();
        AlertDialog shown = builder.create();
        shown.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog = shown;
        shown.setOnDismissListener(ignored -> {
            if (dialog == shown) dialog = null;
        });
        shown.show();
        return shown;
    }

    private void dismissDialog() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private EditText edit(String hint, int limit, boolean multiline) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(INK);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        if (multiline) {
            input.setMinLines(3);
            input.setMaxLines(6);
            input.setGravity(Gravity.TOP);
        }
        return input;
    }

    private CheckBox checkbox(String label, boolean checked) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setChecked(checked);
        check.setMinHeight(dp(48));
        return check;
    }

    private LinearLayout dialogBody() {
        LinearLayout body = column();
        body.setPadding(dp(22), dp(8), dp(22), dp(4));
        return body;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, int background, int foreground, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setTextColor(foreground);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setStateListAnimator(null);
        view.setBackground(buttonBackground(background, 13));
        view.setOnClickListener(ignored -> action.run());
        return view;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private RippleDrawable buttonBackground(int color, int radius) {
        int ripple = color == FOREST || color == 0xff30604f ? 0x33ffffff : 0x24000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), shape(color, radius), null);
    }

    private LinearLayout.LayoutParams margins(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(width, height);
        layout.topMargin = dp(top);
        layout.bottomMargin = dp(bottom);
        return layout;
    }

    private void toast(String message) {
        if (resumed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
