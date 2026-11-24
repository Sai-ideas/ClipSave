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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.pdf.PdfRenderer;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
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
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private static final int PICK_FILE = 10;
    private static final int UNLOCK = 11;
    private static final int ENABLE_LOCK = 12;
    private static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final String[] FILTERS = {"全部", "收藏", "待讀", "文字", "連結", "圖片", "PDF"};
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
    private Set<String> reading = Collections.emptySet();
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
    private PrivateAction privateAction;
    private boolean privateDialog;
    private DraftWorkspace workspace;
    private int nextPrivateRequest = 100;
    private int foregroundVersion;
    private int background, surface, surfaceSoft, outline, ink, muted, accent, onAccent, ripple;

    private record PrivateAction(Runnable task, CancellationSignal signal,
                                 int credentialRequest, boolean verified) {}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        background = getColor(R.color.vault_background);
        surface = getColor(R.color.vault_surface);
        surfaceSoft = getColor(R.color.vault_surface_soft);
        outline = getColor(R.color.vault_outline);
        ink = getColor(R.color.vault_text);
        muted = getColor(R.color.vault_secondary_text);
        accent = getColor(R.color.vault_accent);
        onAccent = getColor(R.color.vault_on_accent);
        ripple = getColor(R.color.vault_ripple);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | (getResources().getBoolean(R.bool.vault_light_bars)
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        }
        vault = Vault.get(this);
        workspace = new DraftWorkspace(this, vault);
        clipboard = getSystemService(ClipboardManager.class);
        preferences = getSharedPreferences("privacy", MODE_PRIVATE);
        locked = preferences.getBoolean("lock", false);
        if (state == null && isImportIntent(getIntent())) pendingShare = getIntent();
        if (locked) showLocked();
        else showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!locked && !workspace.active()) refresh();
        workspace.resumeShelf();
        resumePrivateAction();
        workspace.resumeExport();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && workspace != null) {
            resumePrivateAction();
            workspace.resumeExport();
        }
    }

    @Override protected void onPause() {
        resumed = false;
        foregroundVersion++;
        if (privateDialog) dismissDialog();
        workspace.hidePrivateDraft();
        super.onPause();
    }

    @Override protected void onStop() {
        // The system credential activity can stop this activity before returning its result.
        if (privateAction != null && (privateAction.credentialRequest() == 0 || privateAction.verified())) {
            cancelPrivateAction();
        }
        if (preferences.getBoolean("lock", false) && !locked) {
            locked = true;
            entries = Collections.emptyList();
            reading = Collections.emptySet();
            loaded = false;
            search = "";
            showLocked();
        } else {
            dismissDialog();
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelPrivateAction();
        dismissDialog();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isImportIntent(intent)) {
            cancelPrivateAction();
            workspace.cancelExport();
            pendingShare = intent;
            if (!locked && workspace.active()) openClips();
            else consumeImport();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (privateAction != null && privateAction.credentialRequest() == request) {
            if (result == RESULT_OK) verifyPrivateAction();
            else cancelPrivateAction();
            return;
        }
        if (request == DraftWorkspace.EXPORT_REQUEST) {
            workspace.exportResult(result == RESULT_OK && data != null ? data.getData() : null);
            return;
        }
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

    void openClips() {
        if (busy || locked) return;
        showHome();
        refresh();
    }

    @Override public void onBackPressed() {
        if (!workspace.goBack()) super.onBackPressed();
    }

    private void showHome() {
        workspace.deactivate();
        LinearLayout page = newPage();

        LinearLayout header = row();
        header.addView(iconBadge(R.drawable.ic_clip, 40));
        TextView identity = text("剪存", 26, ink, true);
        identity.setAccessibilityHeading(true);
        LinearLayout.LayoutParams identityLayout = new LinearLayout.LayoutParams(0, -2, 1);
        identityLayout.setMarginStart(dp(12));
        header.addView(identity, identityLayout);
        LinearLayout.LayoutParams settingsLayout = new LinearLayout.LayoutParams(-2, -2);
        settingsLayout.setMarginStart(dp(6));
        header.addView(button("設定", surfaceSoft, accent, this::settings), settingsLayout);
        page.addView(header, margins(-1, -2, 0, 18));

        page.addView(button("儲存目前剪貼簿", accent, onAccent, this::captureClipboard),
                new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = row();
        actions.addView(button("新增文字", surfaceSoft, accent, this::addText),
                new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.setMarginStart(dp(8));
        actions.addView(button("匯入圖片", surfaceSoft, accent, this::pickFile), right);
        page.addView(actions, margins(-1, -2, 8, 0));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setHint("搜尋內容、檔名或標籤");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setPadding(dp(14), 0, dp(14), 0);
        input.setBackground(panel(15));
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
            Button chip = button(FILTERS[i], surfaceSoft, accent, () -> {
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
        listTitle = text("剪輯", 22, ink, true);
        listHeader.addView(listTitle, new LinearLayout.LayoutParams(0, -2, 1));
        listCount = text("", 14, muted, false);
        listHeader.addView(listCount);
        page.addView(listHeader, margins(-1, -2, 0, 8));
        results = column();
        page.addView(results);
        page.setFocusableInTouchMode(true);
        page.requestFocus();
        render();
    }

    private void showLocked() {
        workspace.deactivate();
        startScreen();
        results = null;
        LinearLayout gate = column();
        gate.setGravity(Gravity.CENTER);
        gate.setPadding(dp(24), dp(24), dp(24), dp(24));
        gate.addView(text("剪存已上鎖", 30, ink, true));
        gate.addView(text("使用手機的螢幕鎖定憑證解鎖。", 15, muted, false),
                margins(-2, -2, 8, 16));
        gate.addView(button("解鎖", accent, onAccent, () -> requestCredential(UNLOCK)),
                new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(gate, new LinearLayout.LayoutParams(-1, -1));
    }

    LinearLayout newPage() {
        return newPage(null, false);
    }

    LinearLayout newPage(View toolbar, boolean pinbao) {
        startScreen();
        if (toolbar != null) root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = column();
        page.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(destinationBar(pinbao), new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private LinearLayout destinationBar(boolean pinbao) {
        LinearLayout bar = column();
        bar.setBackgroundColor(surface);
        View divider = new View(this);
        divider.setBackgroundColor(outline);
        bar.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout destinations = row();
        destinations.setPadding(dp(12), dp(4), dp(12), dp(4));
        addDestination(destinations, "剪輯", !pinbao, workspace::switchToClips);
        addDestination(destinations, "拼報", pinbao, workspace::switchToPinbao);
        bar.addView(destinations);
        return bar;
    }

    private void addDestination(LinearLayout row, String label, boolean selected, Runnable navigate) {
        Button destination = button(label, selected ? surfaceSoft : Color.TRANSPARENT,
                selected ? accent : ink, () -> {
                    if (!selected && canAct()) navigate.run();
                });
        destination.setSelected(selected);
        destination.setContentDescription(label);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) layout.setMarginStart(dp(8));
        row.addView(destination, layout);
    }

    private void startScreen() {
        dismissDialog();
        screenVersion++;
        busy = false;
        root = column();
        root.setBackgroundColor(background);
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
            chip.setTextColor(i == filter ? onAccent : ink);
            chip.setBackground(buttonBackground(i == filter ? accent : surfaceSoft, 18));
            chip.setSelected(i == filter);
        }
        listTitle.setText(filter == 0 ? "剪輯" : FILTERS[filter]);
        results.removeAllViews();
        if (!loaded) {
            listCount.setText("");
            if (loadFailed) {
                results.addView(text("無法開啟本機資料庫。", 15, muted, false));
                results.addView(button("重試", surfaceSoft, accent, this::refresh), margins(-2, -2, 8, 0));
            } else {
                results.addView(text("讀取中…", 15, muted, false));
            }
            return;
        }

        String query = search.trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (Vault.Entry entry : entries) {
            if (filter == 1 && !entry.starred()) continue;
            if (filter == 2 && !reading.contains(entry.id())) continue;
            if (filter > 2 && !FILTERS[filter].equals(entry.type())) continue;
            if (!query.isEmpty() && (entry.sensitive()
                    || (!entry.content().toLowerCase(Locale.ROOT).contains(query)
                    && !entry.tag().toLowerCase(Locale.ROOT).contains(query)))) continue;
            count++;
            results.addView(card(entry), margins(-1, -2, 0, 8));
        }
        listCount.setText(count + " 筆");
        if (count == 0) {
            TextView message = text(entries.isEmpty() ? "你的剪輯會顯示在這裡。" : "這個篩選條件沒有結果。",
                    15, muted, false);
            message.setPadding(dp(16), dp(18), dp(16), dp(18));
            message.setBackground(panel(17));
            results.addView(message, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private LinearLayout card(Vault.Entry entry) {
        LinearLayout card = column();
        card.setBackground(panel(18));
        card.setPadding(dp(14), dp(12), dp(14), dp(8));
        String type = entry.type();
        LinearLayout heading = row();
        heading.addView(iconBadge(entry.sensitive() ? R.drawable.ic_lock : switch (type) {
            case "連結" -> R.drawable.ic_link;
            case "圖片" -> R.drawable.ic_image;
            default -> R.drawable.ic_document;
        }, 34));

        LinearLayout details = column();
        String badge = type;
        if (entry.pinned()) badge += "  ·  置頂";
        if (entry.starred()) badge += "  ·  收藏";
        if (reading.contains(entry.id())) badge += "  ·  待讀";
        if (!entry.sensitive() && !entry.tag().isEmpty()) badge += "  ·  " + entry.tag();
        details.addView(text(badge, 13, accent, true));
        details.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(entry.savedAt())), 12, muted, false));
        LinearLayout.LayoutParams detailsLayout = new LinearLayout.LayoutParams(0, -2, 1);
        detailsLayout.setMarginStart(dp(10));
        heading.addView(details, detailsLayout);
        card.addView(heading);

        TextView content = text(entry.sensitive() ? entry.privateTitle() : entry.content(), 17, ink, true);
        if (entry.sensitive()) content.setContentDescription(entry.privateDescription().isEmpty()
                ? "私密剪輯，內容已隱藏" : "私密剪輯，" + entry.privateDescription());
        content.setMaxLines(3);
        content.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(content, margins(-1, -2, 10, 9));

        View divider = new View(this);
        divider.setBackgroundColor(outline);
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout actions = row();
        actions.addView(actionButton("複製", R.drawable.ic_copy, () -> copy(entry)));
        actions.addView(actionButton("查看", entry.sensitive() ? R.drawable.ic_lock : R.drawable.ic_view,
                () -> preview(entry)));
        if (!entry.attachment()) {
            actions.addView(actionButton("編輯", R.drawable.ic_edit,
                    () -> authorize(entry, "編輯私密剪輯", this::showTextEditor)));
        }
        actions.addView(actionButton("更多", R.drawable.ic_more, () -> entryOptions(entry)));
        card.addView(actions, margins(-1, -2, 4, 0));
        return card;
    }

    private void refresh() {
        if (busy || locked) return;
        run(() -> new Snapshot(vault.list(), vault.retentionDays(), vault.reading()), snapshot -> {
            loadFailed = false;
            loaded = true;
            entries = snapshot.entries();
            retentionDays = snapshot.days();
            reading = snapshot.reading();
            render();
            resumePrivateAction();
            consumeImport();
        }, () -> {
            loadFailed = true;
            cancelPrivateAction();
            render();
        });
    }

    private record Snapshot(List<Vault.Entry> entries, int days, Set<String> reading) {}
    private record Capture(String text, Uri uri, String mime, boolean sensitive, boolean fromSelection) {
        Capture(String text, Uri uri, String mime, boolean sensitive) {
            this(text, uri, mime, sensitive, false);
        }
    }

    private static boolean isImportIntent(Intent intent) {
        return Intent.ACTION_SEND.equals(intent.getAction()) || Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
    }

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
                toast("支援文字、網址與圖片。");
            }
        } catch (RuntimeException error) {
            toast("無法讀取目前的剪貼簿。");
        }
    }

    private void addText() {
        showTextEditor(null);
    }

    private void showTextEditor(Vault.Entry entry) {
        if (busy) return;
        LinearLayout body = dialogBody();
        EditText input = edit("輸入文字或連結", Vault.MAX_TEXT, true);
        body.addView(input);
        CheckBox mask = checkbox("標為私密", false);
        EditText description;
        if (entry == null) {
            body.addView(mask);
            description = addPrivateDescription(body, "", mask);
        } else {
            input.setText(entry.content());
            description = null;
        }
        AlertDialog prompt = show(new AlertDialog.Builder(this)
                .setTitle(entry == null ? "新增文字" : "編輯內容").setView(dialogScroll(body))
                .setNegativeButton("取消", null).setPositiveButton("儲存", null));
        privateDialog = entry != null && entry.sensitive();
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (busy) return;
            String value = input.getText().toString();
            if (value.trim().isEmpty()) {
                input.setError("請輸入文字");
                return;
            }
            if (entry == null) {
                prompt.dismiss();
                save(new Capture(value, null, null, mask.isChecked()), mask.isChecked(),
                        description.getText().toString());
            } else {
                mutate(() -> { vault.updateText(entry.id(), value); return null; }, "已更新內容", () -> {
                    if (dialog == prompt) dismissDialog();
                });
            }
        });
    }

    private void pickFile() {
        if (busy) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(picker, PICK_FILE);
        } catch (RuntimeException error) {
            toast("裝置上沒有可用的檔案選擇器。");
        }
    }

    private void consumeImport() {
        if (locked || !resumed || busy || privateAction != null || privateDialog) return;
        if (workspace.active()) {
            if (pendingShare != null) openClips();
            return;
        }
        if (!loaded) return;
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
                boolean fromSelection = Intent.ACTION_PROCESS_TEXT.equals(share.getAction());
                CharSequence value = share.getCharSequenceExtra(
                        fromSelection ? Intent.EXTRA_PROCESS_TEXT : Intent.EXTRA_TEXT);
                if (value != null) {
                    offer(new Capture(value.toString(), null, null, sensitive, fromSelection));
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
        toast("無法收錄分享內容。支援文字、網址與圖片。");
    }

    private void offer(Capture capture) {
        if (capture.text() != null && capture.text().length() > Vault.MAX_TEXT) {
            toast("文字超過 20,000 個字元。");
            return;
        }
        LinearLayout body = dialogBody();
        String message = capture.uri() != null ? "將附件複製到本機加密資料庫。"
                : "將這段文字保存到本機加密資料庫。";
        body.addView(text(message, 16, ink, false));
        CheckBox mask = checkbox("標為私密", capture.sensitive());
        mask.setEnabled(!capture.sensitive());
        body.addView(mask);
        EditText description = addPrivateDescription(body, "", mask);
        show(new AlertDialog.Builder(this).setTitle("收錄剪輯").setView(dialogScroll(body))
                .setNegativeButton("取消", (d, which) -> {
                    if (capture.fromSelection()) finish();
                })
                .setPositiveButton("儲存", (d, which) -> save(capture, mask.isChecked(),
                        description.getText().toString())));
    }

    private void save(Capture capture, boolean sensitive, String description) {
        mutate(() -> {
            if (capture.uri() == null) vault.addText(capture.text(), sensitive, description);
            else importAttachment(capture.uri(), capture.mime(), sensitive, description);
            return null;
        }, "已儲存", () -> {
            if (capture.fromSelection()) finish();
        });
    }

    private void importAttachment(Uri uri, String hint, boolean sensitive, String description) throws Exception {
        if (!isContent(uri)) throw new Vault.Limit("無法讀取此附件。");
        String mime = getContentResolver().getType(uri);
        if (mime == null || mime.equals("application/octet-stream")) mime = hint;
        if (!Vault.supportsAttachment(mime)) throw new Vault.Limit("只能收錄圖片附件。");

        String name = "圖片";
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
                vault.addAttachment(mime, name, data, sensitive, description);
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
        authorize(entry, "複製私密剪輯", this::copyToClipboard);
    }

    private void copyToClipboard(Vault.Entry entry) {
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
    }

    private void preview(Vault.Entry entry) {
        authorize(entry, "查看私密剪輯", this::openPreview);
    }

    private void openPreview(Vault.Entry entry) {
        if (!entry.attachment()) {
            ScrollView scroll = new ScrollView(this);
            scroll.setPadding(dp(22), dp(4), dp(22), dp(12));
            scroll.addView(text(entry.content(), 16, ink, false));
            show(new AlertDialog.Builder(this).setTitle(entry.type()).setView(scroll)
                    .setPositiveButton("關閉", null));
            privateDialog = entry.sensitive();
            return;
        }
        int visibleVersion = foregroundVersion;
        run(() -> previewBitmap(entry), bitmap -> {
            if (!resumed || (entry.sensitive() && visibleVersion != foregroundVersion)) return;
            LinearLayout body = dialogBody();
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageBitmap(bitmap);
            image.setContentDescription(entry.type() + "預覽");
            body.addView(image, new LinearLayout.LayoutParams(-1, -2));
            if (entry.mime().equals("application/pdf")) body.addView(text("第 1 頁", 13, muted, false));
            show(new AlertDialog.Builder(this).setTitle(entry.sensitive() ? "預覽" : entry.content())
                    .setView(body).setPositiveButton("關閉", null));
            privateDialog = entry.sensitive();
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
        String organize = entry.sensitive() ? "整理剪輯（含顯示描述）" : "置頂、收藏與標籤";
        ArrayList<String> options = new ArrayList<>(Arrays.asList(organize, "分享", "刪除"));
        String type = entry.type();
        if (!type.equals("PDF")) options.add("加入拼報");
        boolean waiting = reading.contains(entry.id());
        if (type.equals("連結")) {
            options.add("開啟網址");
            options.add(waiting ? "標為已讀" : "加入待讀");
        }
        int privacyOption = options.size();
        if (!entry.sensitive()) options.add("設為私密");
        show(new AlertDialog.Builder(this).setTitle("剪輯選項")
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == privacyOption) {
                        protectEntry(entry);
                        return;
                    }
                    switch (which) {
                        case 0 -> authorize(entry, "編輯私密剪輯設定", this::editOptions);
                        case 1 -> share(entry);
                        case 2 -> confirm("刪除剪輯", "這筆剪輯將從歷史清單移除。", "刪除",
                                () -> mutate(() -> { vault.delete(entry.id()); return null; }, "已刪除"));
                        case 3 -> workspace.pickDraft(entry);
                        case 4 -> authorize(entry, "開啟私密網址", current -> openLink(current.content()));
                        case 5 -> mutate(() -> {
                            vault.setReading(entry.id(), !waiting);
                            return null;
                        }, waiting ? "已標為已讀，套用一般保留期限。" : "已加入待讀");
                        default -> {}
                    }
                }));
    }

    private void protectEntry(Vault.Entry entry) {
        LinearLayout body = dialogBody();
        body.addView(text("這筆剪輯和已加入拼報的副本都會受到保護。設為私密後無法轉回一般剪輯。",
                16, ink, false));
        EditText description = addPrivateDescription(body, "", null);
        show(new AlertDialog.Builder(this).setTitle("設為私密").setView(dialogScroll(body))
                .setNegativeButton("取消", null).setPositiveButton("設為私密", (dialog, which) -> {
                    String value = description.getText().toString();
                    mutate(() -> {
                        if (entry.attachment()) {
                            revokeUriPermission(AttachmentProvider.uri(this, entry.id()),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                        vault.protectEntry(entry.id(), value);
                        return null;
                    }, "已設為私密");
                }));
    }

    void openLink(String value) {
        if (!Vault.isWebLink(value)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value.trim())));
        } catch (RuntimeException error) {
            toast("無法開啟網址。");
        }
    }

    private void editOptions(Vault.Entry entry) {
        LinearLayout body = dialogBody();
        CheckBox pin = checkbox("置頂並保留到手動刪除", entry.pinned());
        CheckBox star = checkbox("加入收藏", entry.starred());
        EditText tag = edit("自訂標籤", 40, false);
        tag.setText(entry.tag());
        body.addView(pin);
        body.addView(star);
        body.addView(tag);
        EditText description = entry.sensitive() ? addPrivateDescription(body, entry.privateDescription(), null) : null;
        show(new AlertDialog.Builder(this).setTitle("整理剪輯").setView(dialogScroll(body))
                .setNegativeButton("取消", null).setPositiveButton("儲存", (d, which) -> {
                    boolean pinned = pin.isChecked();
                    boolean starred = star.isChecked();
                    String label = tag.getText().toString().trim();
                    String display = description == null ? null : description.getText().toString();
                    mutate(() -> {
                        vault.updateOptions(entry.id(), pinned, starred, label, display);
                        return null;
                    }, "已更新");
                }));
        privateDialog = entry.sensitive();
    }

    private EditText addPrivateDescription(LinearLayout body, String value, CheckBox mask) {
        LinearLayout field = column();
        EditText input = edit("顯示描述（選填）", Vault.MAX_PRIVATE_DESCRIPTION, false);
        input.setText(value);
        field.addView(input);
        field.addView(text("驗證前可見，留空顯示 ****", 13, muted, false));
        if (mask != null) {
            field.setVisibility(mask.isChecked() ? View.VISIBLE : View.GONE);
            mask.setOnCheckedChangeListener((button, checked) -> field.setVisibility(checked ? View.VISIBLE : View.GONE));
        }
        body.addView(field, margins(-1, -2, 8, 0));
        return input;
    }

    private ScrollView dialogScroll(LinearLayout body) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        return scroll;
    }

    private void share(Vault.Entry entry) {
        authorize(entry, "分享私密剪輯", this::openShareSheet);
    }

    private void openShareSheet(Vault.Entry entry) {
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
                    if (which == 2) confirm("刪除所有剪輯", "歷史清單中的文字與附件會移除，包含置頂與收藏。拼報內的副本會保留。", "全部刪除",
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

    private void authorize(Vault.Entry entry, String title, Consumer<Vault.Entry> task) {
        authorize(entry.sensitive(), title, () -> {
            if (entry.sensitive()) runForeground(() -> vault.entry(entry.id()), task);
            else task.accept(entry);
        });
    }

    void authorize(boolean sensitive, String title, Runnable task) {
        if (busy || locked || !resumed || privateAction != null) return;
        if (!sensitive) {
            task.run();
            return;
        }
        dismissDialog();
        CancellationSignal signal = new CancellationSignal();
        privateAction = new PrivateAction(task, signal, 0, false);
        BiometricManager biometrics = getSystemService(BiometricManager.class);
        int strong = BiometricManager.Authenticators.BIOMETRIC_STRONG;
        boolean biometricReady = biometrics != null && (Build.VERSION.SDK_INT >= 30
                ? biometrics.canAuthenticate(strong) : biometrics.canAuthenticate())
                == BiometricManager.BIOMETRIC_SUCCESS;
        if (!biometricReady) {
            requestPrivateCredential(title);
            return;
        }
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        boolean hasCredential = keyguard != null && keyguard.isDeviceSecure();
        BiometricPrompt.Builder prompt = new BiometricPrompt.Builder(this)
                .setTitle(title)
                .setNegativeButton(hasCredential ? "使用螢幕鎖定" : "取消", getMainExecutor(),
                        (dialog, which) -> {
                            if (!isCurrentPrompt(signal)) return;
                            if (hasCredential) requestPrivateCredential(title);
                            else cancelPrivateAction();
                        });
        if (Build.VERSION.SDK_INT >= 30) prompt.setAllowedAuthenticators(strong);
        try {
            prompt.build().authenticate(signal, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    if (isCurrentPrompt(signal)) verifyPrivateAction();
                }

                @Override public void onAuthenticationError(int code, CharSequence message) {
                    if (!isCurrentPrompt(signal)) return;
                    if (hasCredential && (code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
                            || code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT
                            || code == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE)) {
                        requestPrivateCredential(title);
                    } else {
                        cancelPrivateAction();
                    }
                }
            });
        } catch (RuntimeException error) {
            requestPrivateCredential(title);
        }
    }

    private boolean isCurrentPrompt(CancellationSignal signal) {
        return privateAction != null && privateAction.signal() == signal && !privateAction.verified();
    }

    private void requestPrivateCredential(String title) {
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        Intent credential = keyguard == null ? null
                : keyguard.createConfirmDeviceCredentialIntent(title, "驗證後才能存取私密內容");
        if (credential == null) {
            cancelPrivateAction();
            toast("請先在 Android 設定中啟用螢幕鎖定。");
            return;
        }
        int request = nextPrivateRequest++;
        PrivateAction action = privateAction;
        privateAction = new PrivateAction(action.task(), null, request, false);
        try {
            startActivityForResult(credential, request);
        } catch (RuntimeException error) {
            cancelPrivateAction();
            toast("無法開啟裝置驗證。");
        }
    }

    private void verifyPrivateAction() {
        PrivateAction action = privateAction;
        privateAction = new PrivateAction(action.task(), null, 0, true);
        resumePrivateAction();
    }

    private void resumePrivateAction() {
        if (privateAction == null || !privateAction.verified() || busy || !resumed || !hasWindowFocus()) return;
        if (locked) {
            locked = false;
            showHome();
            refresh();
            return;
        }
        PrivateAction action = privateAction;
        privateAction = null;
        action.task().run();
    }

    private void cancelPrivateAction() {
        PrivateAction action = privateAction;
        privateAction = null;
        if (action != null && action.signal() != null) action.signal().cancel();
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
        mutate(action, success, () -> {});
    }

    private void mutate(Callable<Void> action, String success, Runnable afterSave) {
        run(() -> {
            action.call();
            return new Snapshot(vault.list(), vault.retentionDays(), vault.reading());
        }, snapshot -> {
            entries = snapshot.entries();
            retentionDays = snapshot.days();
            reading = snapshot.reading();
            loaded = true;
            render();
            toast(success);
            afterSave.run();
            resumePrivateAction();
            consumeImport();
        }, null);
    }

    boolean canAct() {
        return resumed && !locked && !busy;
    }

    void copyArticle(String title, String body) {
        try {
            ClipData clip = ClipData.newPlainText(title, body);
            PersistableBundle options = new PersistableBundle();
            options.putBoolean(SENSITIVE, true);
            clip.getDescription().setExtras(options);
            clipboard.setPrimaryClip(clip);
            if (Build.VERSION.SDK_INT < 33) toast("已複製文章文字。");
        } catch (RuntimeException error) {
            toast("文章過長，請改用匯出。");
        }
    }

    <T> void runForeground(Callable<T> operation, Consumer<T> done) {
        runForeground(operation, done, null);
    }

    <T> void runForeground(Callable<T> operation, Consumer<T> done, Runnable failure) {
        int visibleVersion = foregroundVersion;
        run(operation, result -> {
            if (resumed && visibleVersion == foregroundVersion) done.accept(result);
        }, failure);
    }

    <T> void run(Callable<T> operation, Consumer<T> done, Runnable failure) {
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
                    resumePrivateAction();
                    workspace.resumeExport();
                    if (pendingShare != null && workspace.active()) consumeImport();
                });
            } catch (Exception error) {
                String message = error instanceof Vault.Limit ? error.getMessage() : "操作失敗，請重試。";
                runOnUiThread(() -> {
                    if (version != screenVersion || isDestroyed()) return;
                    busy = false;
                    toast(message);
                    if (failure != null) failure.run();
                    resumePrivateAction();
                    workspace.resumeExport();
                    if (pendingShare != null && workspace.active()) consumeImport();
                });
            }
        });
    }

    void confirm(String title, String message, String action, Runnable task) {
        confirm(title, message, action, task, false);
    }

    void confirm(String title, String message, String action, Runnable task, boolean sensitive) {
        show(new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null).setPositiveButton(action, (d, which) -> task.run()));
        privateDialog = sensitive;
    }

    AlertDialog show(AlertDialog.Builder builder) {
        dismissDialog();
        AlertDialog shown = builder.create();
        shown.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog = shown;
        shown.setOnDismissListener(ignored -> {
            if (dialog == shown) {
                dialog = null;
                privateDialog = false;
            }
        });
        shown.show();
        return shown;
    }

    private void dismissDialog() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
        privateDialog = false;
    }

    EditText edit(String hint, int limit, boolean multiline) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
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

    CheckBox checkbox(String label, boolean checked) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setChecked(checked);
        check.setMinHeight(dp(48));
        return check;
    }

    LinearLayout dialogBody() {
        LinearLayout body = column();
        body.setPadding(dp(22), dp(8), dp(22), dp(4));
        return body;
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    Button button(String label, int background, int foreground, Runnable action) {
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

    GradientDrawable panel(int radius) {
        GradientDrawable drawable = shape(surface, radius);
        drawable.setStroke(dp(1), outline);
        return drawable;
    }

    private ImageView iconBadge(int resource, int size) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(resource);
        icon.setImageTintList(ColorStateList.valueOf(accent));
        icon.setBackground(shape(surfaceSoft, 10));
        int inset = dp((size - 22) / 2);
        icon.setPadding(inset, inset, inset, inset);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private Button actionButton(String label, int resource, Runnable action) {
        Button view = button(label, surface, ink, action);
        view.setTextSize(12);
        view.setPadding(0, dp(5), 0, dp(5));
        Drawable symbol = getDrawable(resource).mutate();
        symbol.setTint(accent);
        symbol.setBounds(0, 0, dp(19), dp(19));
        view.setCompoundDrawablesRelative(null, symbol, null, null);
        view.setCompoundDrawablePadding(dp(2));
        view.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1));
        return view;
    }

    private RippleDrawable buttonBackground(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(color == accent ? 0x36000000 : ripple),
                shape(color, radius), color == Color.TRANSPARENT ? shape(Color.WHITE, radius) : null);
    }

    LinearLayout.LayoutParams margins(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(width, height);
        layout.topMargin = dp(top);
        layout.bottomMargin = dp(bottom);
        return layout;
    }

    void toast(String message) {
        if (resumed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
