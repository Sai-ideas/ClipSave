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
import android.graphics.ImageDecoder;
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
import android.widget.ImageButton;
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
    private static final String SENSITIVE = "android.content.extra.IS_SENSITIVE";
    private static final String VERIFIED_COPY = "dev.local.clipvault.VERIFIED_COPY";
    private static final String[] FILTERS = {"全部", "文字", "連結", "圖片"};
    private static final int[] RETENTION = {7, 30, 90, 0};
    private static final String[] RETENTION_NAMES = {"7 天", "30 天", "90 天", "永久保留"};
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private final ArrayList<Button> filters = new ArrayList<>();
    private Vault vault;
    private ClipboardManager clipboard;
    private ClipboardReceipt clipboardReceipt;
    private SharedPreferences preferences;
    private LinearLayout root;
    private LinearLayout results;
    private TextView listTitle;
    private TextView listCount;
    private List<Vault.Entry> entries = Collections.emptyList();
    private AlertDialog dialog;
    private AlertDialog overlay;
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
    private boolean editingDialog;
    private Runnable saveBeforeBackground;
    private boolean keepDialog;
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
        if (saveBeforeBackground != null) saveBeforeBackground.run();
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
            loaded = false;
            search = "";
            showLocked();
        } else if (!editingDialog && !keepDialog) {
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
            if (!locked && workspace.active() && !editingDialog && !keepDialog) openClips();
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
        LinearLayout toolbar = toolbarContainer();
        LinearLayout header = row();
        TextView title = text("剪輯", 20, ink, true);
        title.setAccessibilityHeading(true);
        LinearLayout.LayoutParams heading = new LinearLayout.LayoutParams(0, -2, 1);
        heading.setMarginStart(dp(8));
        header.addView(title, heading);
        header.addView(toolbarControl("設定", "剪存設定", this::settings));
        toolbar.addView(header);

        LinearLayout actions = row();
        addToolbarAction(actions, "收錄", "儲存目前剪貼簿", 1, this::captureClipboard);
        addToolbarAction(actions, "新增", "新增文字", 1, this::addText);
        addToolbarAction(actions, "匯入", "匯入圖片", 1, this::pickFile);
        toolbar.addView(actions);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setTextColor(ink);
        input.setHintTextColor(muted);
        input.setHint("搜尋標題、內容、檔名或標籤");
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
        toolbar.addView(input, margins(-1, dp(48), 0, 4));

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
        toolbar.addView(strip, margins(-1, -2, 0, 2));
        LinearLayout page = newPage(toolbar, false);

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

    LinearLayout toolbarContainer() {
        LinearLayout bar = column();
        bar.setBackgroundColor(surface);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        return bar;
    }

    Button toolbarControl(String label, String description, Runnable action) {
        Button button = button(label, Color.TRANSPARENT, ink, () -> {
            if (canAct()) action.run();
        });
        button.setTextSize(13);
        button.setPadding(dp(3), dp(4), dp(3), dp(4));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setContentDescription(description);
        return button;
    }

    Button addToolbarAction(LinearLayout row, String label, String description, float weight, Runnable action) {
        Button control = toolbarControl(label, description, action);
        row.addView(control, new LinearLayout.LayoutParams(0, -2, weight));
        return control;
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
        listTitle.setText(FILTERS[filter]);
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
            if (!matchesFilter(entry)) continue;
            if (!query.isEmpty() && (entry.sensitive()
                    || (!entry.content().toLowerCase(Locale.ROOT).contains(query)
                    && !entry.url().toLowerCase(Locale.ROOT).contains(query)
                    && !entry.title().toLowerCase(Locale.ROOT).contains(query)
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

    private boolean matchesFilter(Vault.Entry entry) {
        return switch (FILTERS[filter]) {
            case "全部" -> true;
            case "連結" -> !entry.url().isEmpty();
            default -> FILTERS[filter].equals(entry.type());
        };
    }

    private LinearLayout card(Vault.Entry entry) {
        LinearLayout card = column();
        card.setBackground(panel(18));
        card.setPadding(dp(14), dp(12), dp(14), dp(8));
        String type = entry.type();
        LinearLayout heading = row();
        heading.addView(iconBadge(entry.sensitive() ? R.drawable.ic_lock : switch (type) {
            case "圖片" -> R.drawable.ic_image;
            default -> R.drawable.ic_document;
        }, 34));

        LinearLayout details = column();
        String badge = entry.displayTitle();
        if (entry.pinned()) badge += "  ·  置頂";
        if (!entry.sensitive() && !entry.tag().isEmpty()) badge += "  ·  " + entry.tag();
        TextView cardTitle = text(badge, 13, accent, true);
        cardTitle.setMaxLines(1);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        details.addView(cardTitle);
        details.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(entry.savedAt())), 12, muted, false));
        LinearLayout.LayoutParams detailsLayout = new LinearLayout.LayoutParams(0, -2, 1);
        detailsLayout.setMarginStart(dp(10));
        heading.addView(details, detailsLayout);
        card.addView(heading);

        TextView content = text(entry.sensitive() ? "內容已隱藏" : entry.content(), 17,
                entry.sensitive() ? muted : ink, !entry.sensitive());
        if (entry.sensitive()) content.setContentDescription("私密剪輯，查看需要驗證");
        content.setMinHeight(dp(48));
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setBackground(buttonBackground(Color.TRANSPARENT, 10));
        content.setFocusable(true);
        content.setOnClickListener(view -> preview(entry));
        content.setMaxLines(3);
        content.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(content, margins(-1, -2, 10, 9));

        if (!entry.sensitive() && Vault.isWebLink(entry.url())) {
            String host = java.net.URI.create(entry.url()).getHost();
            Button preview = button("網址預覽 · " + host, surfaceSoft, accent, () -> previewLink(entry));
            preview.setMaxLines(1);
            preview.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(preview, margins(-2, -2, 0, 8));
        }

        View divider = new View(this);
        divider.setBackgroundColor(outline);
        card.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout actions = row();
        actions.addView(actionButton("複製", R.drawable.ic_copy, () -> copy(entry)));
        Button open = actionButton("開啟", R.drawable.ic_link,
                () -> authorize(entry, "開啟私密網址", current -> openLink(current.url())));
        open.setContentDescription("開啟連結");
        if (entry.url().isEmpty()) {
            open.setEnabled(false);
            open.setTextColor(muted);
            for (Drawable icon : open.getCompoundDrawablesRelative()) {
                if (icon != null) icon.setTint(muted);
            }
            open.setAlpha(0.6f);
        }
        actions.addView(open);
        actions.addView(pinAction(entry));
        if (entry.canEditUrl()) {
            Button add = actionButton("加入", R.drawable.ic_add_pinbao, () -> workspace.pickDraft(entry));
            add.setContentDescription("加入拼報");
            actions.addView(add);
        }
        Button protect = actionButton("私密", R.drawable.ic_lock, () -> protectEntry(entry));
        protect.setEnabled(!entry.sensitive());
        protect.setSelected(entry.sensitive());
        protect.setContentDescription(entry.sensitive() ? "已設為私密" : "設為私密");
        protect.setBackground(buttonBackground(entry.sensitive() ? surfaceSoft : surface, 13));
        actions.addView(protect);
        actions.addView(actionButton("更多", R.drawable.ic_more, () -> entryOptions(entry)));
        if (getResources().getConfiguration().screenWidthDp >= 360) {
            card.addView(actions, margins(-1, -2, 4, 0));
        } else {
            HorizontalScrollView overflow = new HorizontalScrollView(this);
            overflow.setFillViewport(true);
            overflow.addView(actions, new android.widget.FrameLayout.LayoutParams(-2, -2));
            card.addView(overflow, margins(-1, -2, 4, 0));
        }
        return card;
    }

    private Button pinAction(Vault.Entry entry) {
        boolean pinned = entry.pinned();
        Button action = actionButton("置頂", R.drawable.ic_pin,
                () -> authorize(entry, "整理私密剪輯", current -> {
                    boolean next = !current.pinned();
                    mutate(() -> {
                        vault.setPinned(current.id(), next);
                        return null;
                    }, next ? "已置頂" : "已取消置頂");
                }));
        action.setBackground(buttonBackground(pinned ? surfaceSoft : surface, 13));
        action.setSelected(pinned);
        action.setContentDescription(pinned ? "取消置頂" : "置頂");
        if (Build.VERSION.SDK_INT >= 30) action.setStateDescription(pinned ? "已置頂" : "未置頂");
        return action;
    }

    private void refresh() {
        if (busy || locked) return;
        run(() -> new Snapshot(vault.list(), vault.retentionDays()), snapshot -> {
            loadFailed = false;
            loaded = true;
            entries = snapshot.entries();
            retentionDays = snapshot.days();
            render();
            resumePrivateAction();
            consumeImport();
        }, () -> {
            loadFailed = true;
            cancelPrivateAction();
            render();
        });
    }

    private record Snapshot(List<Vault.Entry> entries, int days) {}
    private record Capture(String text, Uri uri, String mime, boolean sensitive, boolean fromSelection,
                           boolean allowGeneralCopy) {
        Capture(String text, Uri uri, String mime, boolean sensitive) {
            this(text, uri, mime, sensitive, false, false);
        }
    }

    private record ClipboardReceipt(String token, long timestamp, String text, Uri uri) {
        boolean matches(ClipData clip) {
            ClipDescription description = clip.getDescription();
            PersistableBundle extras = description.getExtras();
            if (clip.getItemCount() != 1 || extras == null || timestamp <= 0
                    || timestamp != description.getTimestamp() || !token.equals(extras.getString(VERIFIED_COPY))) {
                return false;
            }
            return java.util.Objects.equals(text, clipboardText(clip.getItemAt(0)))
                    && java.util.Objects.equals(uri, clip.getItemAt(0).getUri());
        }
    }

    private static String clipboardText(ClipData.Item item) {
        CharSequence text = item.getText();
        return text == null ? null : text.toString();
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
            boolean allowGeneral = sensitive && clipboardReceipt != null && clipboardReceipt.matches(clip);
            if (!allowGeneral) clipboardReceipt = null;
            CharSequence value = item.getText();
            if (value != null && value.length() > 0) {
                offer(new Capture(value.toString(), null, null, sensitive, false, allowGeneral));
            } else if (isContent(item.getUri())) {
                String mime = null;
                for (int i = 0; i < description.getMimeTypeCount(); i++) {
                    String candidate = description.getMimeType(i);
                    if (Vault.supportsAttachment(candidate)) {
                        mime = candidate;
                        break;
                    }
                }
                offer(new Capture(null, item.getUri(), mime, sensitive, false, allowGeneral));
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
        String original = entry == null ? "" : entry.content();
        String primary = entry == null ? "" : entry.url();
        TextFormat originalFormat = entry == null ? TextFormat.MARKDOWN : entry.format();
        ContentEditor editor = new ContentEditor(this, "輸入 Markdown 內文", original, Vault.MAX_TEXT, true);
        editor.enableReading(() -> {
            String value = editor.input.getText().toString();
            String source = value.trim().isEmpty() ? primary : value;
            return Article.textMarkdown(source, original.equals(source) ? originalFormat : TextFormat.MARKDOWN);
        }, entry != null);
        editor.show(entry == null ? "新增文字" : "剪輯內容", true,
                () -> entry != null && entry.sensitive(), () -> !primary.isEmpty(), value -> {
                    String content = value.trim().isEmpty() ? primary : value;
                    TextFormat format = original.equals(content) ? originalFormat : TextFormat.MARKDOWN;
                    saveEditor(() -> {
                        if (entry == null) vault.addText(content, format, false, "");
                        else vault.updateText(entry.id(), content, format);
                        return null;
                    }, entry == null ? "已儲存" : "已更新內容");
                });
    }

    private void saveEditor(Callable<Void> change, String message) {
        AlertDialog prompt = dialog;
        mutate(change, message, () -> {
            if (dialog == prompt) dismissDialog();
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
        if (locked || !resumed || busy || privateAction != null || privateDialog || editingDialog || keepDialog) return;
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
                    offer(new Capture(value.toString(), null, null, sensitive, fromSelection, false));
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
        mask.setEnabled(!capture.sensitive() || capture.allowGeneralCopy());
        body.addView(mask);
        EditText description = addDisplayTitle(body, "", mask, true);
        show(new AlertDialog.Builder(this).setTitle("收錄剪輯").setView(dialogScroll(body))
                .setNegativeButton("取消", (d, which) -> {
                    if (capture.fromSelection()) finish();
                })
                .setPositiveButton("儲存", (d, which) -> save(capture, mask.isChecked(),
                        description.getText().toString())));
    }

    private void save(Capture capture, boolean sensitive, String description) {
        mutate(() -> {
            if (capture.uri() != null) importAttachment(capture.uri(), capture.mime(), sensitive, description);
            else if (capture.allowGeneralCopy() && !sensitive) vault.addGeneralCopy(capture.text());
            else vault.addText(capture.text(), sensitive, description);
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
            writeClipboard(clip, entry.sensitive());
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
            showTextEditor(entry);
            return;
        }
        int visibleVersion = foregroundVersion;
        run(() -> previewBitmap(entry), bitmap -> {
            if (!resumed || (entry.sensitive() && visibleVersion != foregroundVersion)) return;
            boolean pdf = entry.mime().equals("application/pdf");
            if (pdf) {
                LinearLayout body = dialogBody();
                ImageView image = new ImageView(this);
                image.setAdjustViewBounds(true);
                image.setImageBitmap(bitmap);
                image.setContentDescription("PDF 預覽");
                body.addView(image, new LinearLayout.LayoutParams(-1, -2));
                body.addView(text("第 1 頁", 13, muted, false));
                show(new AlertDialog.Builder(this).setTitle("PDF").setView(body)
                        .setPositiveButton("關閉", null), entry.sensitive());
                return;
            }
            ZoomImage image = new ZoomImage(this, bitmap);
            AlertDialog viewer = show(new AlertDialog.Builder(this).setTitle("圖片預覽").setView(ContentEditor.fillHeight(image))
                    .setNeutralButton("重設縮放", null).setPositiveButton("關閉", null), entry.sensitive());
            ContentEditor.fullscreen(this, viewer);
            viewer.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> image.reset());
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
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)),
                    (decoder, info, source) -> {
                        android.util.Size size = info.getSize();
                        int longest = Math.max(size.getWidth(), size.getHeight());
                        if (longest > 2400) {
                            float ratio = 2400f / longest;
                            decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * ratio)),
                                    Math.max(1, Math.round(size.getHeight() * ratio)));
                        }
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
        } catch (java.io.IOException error) {
            throw new Vault.Limit("無法預覽這個圖片格式。");
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private void entryOptions(Vault.Entry entry) {
        if (busy) return;
        ArrayList<String> options = new ArrayList<>(Arrays.asList("標題與標籤", "刪除"));
        if (entry.canEditUrl()) options.add("編輯網址");
        if (entry.sensitive() && !entry.url().isEmpty()) options.add("網址預覽");
        show(new AlertDialog.Builder(this).setTitle("剪輯選項")
                .setItems(options.toArray(new String[0]), (d, which) -> {
                    switch (options.get(which)) {
                        case "刪除" -> confirm("刪除剪輯", "這筆剪輯將從歷史清單移除。", "刪除",
                                () -> mutate(() -> { vault.delete(entry.id()); return null; }, "已刪除"));
                        case "編輯網址" -> authorize(entry, "編輯私密網址", this::editUrl);
                        case "網址預覽" -> previewLink(entry);
                        default -> authorize(entry, "編輯私密剪輯設定", this::editOptions);
                    }
                }));
    }

    private void previewLink(Vault.Entry entry) {
        authorize(entry, "預覽私密網址", current -> {
            if (!Vault.isWebLink(current.url())) {
                toast("這張剪輯沒有可預覽的主要網址。");
                return;
            }
            new LinkPreviewDialog(this, current).show();
        });
    }

    private void editUrl(Vault.Entry entry) {
        ContentEditor editor = new ContentEditor(this, "主要網址（留空可清除）", entry.url(), Vault.MAX_LINK, false);
        editor.input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.show("編輯網址", false, entry::sensitive, () -> true, value -> {
            String url = value.trim();
            if (!url.isEmpty() && !Vault.isWebLink(url)) {
                editor.input.setError("請輸入有效的 HTTP 或 HTTPS 網址");
                return;
            }
            saveEditor(() -> {
                vault.updateUrl(entry.id(), url);
                return null;
            }, url.isEmpty() ? "已清除網址" : "已更新網址");
        });
    }

    private void protectEntry(Vault.Entry entry) {
        LinearLayout body = dialogBody();
        body.addView(text("此卡設為私密後無法解除。通過驗證後，可複製內容並重新收錄為一般副本。",
                16, ink, false));
        EditText title = addDisplayTitle(body, entry.title(), null, true);
        show(new AlertDialog.Builder(this).setTitle("設為私密").setView(dialogScroll(body))
                .setNegativeButton("取消", null).setPositiveButton("設為私密", (dialog, which) -> {
                    String value = title.getText().toString();
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
        EditText title = addDisplayTitle(body, entry.title(), null, entry.sensitive());
        EditText tag = edit("自訂標籤", 40, false);
        tag.setText(entry.tag());
        body.addView(tag);
        AlertDialog prompt = show(new AlertDialog.Builder(this).setTitle("標題與標籤").setView(dialogScroll(body))
                .setNegativeButton("取消", null).setPositiveButton("儲存", null), entry.sensitive());
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!canAct()) return;
            String label = tag.getText().toString().trim();
            String display = title.getText().toString();
            saveEditor(() -> {
                vault.updateDetails(entry.id(), label, display);
                return null;
            }, "已更新");
        });
    }

    private EditText addDisplayTitle(LinearLayout body, String value, CheckBox mask, boolean sensitive) {
        LinearLayout field = column();
        EditText input = edit("顯示標題（選填）", Vault.MAX_CLIP_TITLE, false);
        input.setText(value);
        field.addView(input);
        if (sensitive) field.addView(text("標題在解鎖前可見，留空顯示 ****", 13, muted, false));
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
                    if (which == 2) confirm("刪除所有剪輯", "歷史清單中的文字與附件會移除，包含置頂項目。拼報內的副本會保留。", "全部刪除",
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
            return new Snapshot(vault.list(), vault.retentionDays());
        }, snapshot -> {
            entries = snapshot.entries();
            retentionDays = snapshot.days();
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

    void copyArticle(String title, String body, boolean sensitive) {
        try {
            ClipData clip = ClipData.newPlainText(title, body);
            writeClipboard(clip, sensitive);
            if (Build.VERSION.SDK_INT < 33) toast("已複製文章文字。");
        } catch (RuntimeException error) {
            toast("文章過長，請改用匯出。");
        }
    }

    private void writeClipboard(ClipData clip, boolean sensitive) {
        clipboardReceipt = null;
        String token = sensitive ? java.util.UUID.randomUUID().toString() : "";
        if (sensitive) {
            PersistableBundle options = new PersistableBundle();
            options.putBoolean(SENSITIVE, true);
            options.putString(VERIFIED_COPY, token);
            clip.getDescription().setExtras(options);
        }
        clipboard.setPrimaryClip(clip);
        if (!sensitive) return;

        // A platform timestamp ties the receipt to this write even if an app reuses the extras.
        try {
            ClipDescription stored = clipboard.getPrimaryClipDescription();
            PersistableBundle extras = stored == null ? null : stored.getExtras();
            if (extras != null && token.equals(extras.getString(VERIFIED_COPY))) {
                ClipData.Item item = clip.getItemAt(0);
                clipboardReceipt = new ClipboardReceipt(token, stored.getTimestamp(), clipboardText(item), item.getUri());
            }
        } catch (RuntimeException ignored) {
            // The system already holds the sensitive clip; it can still be captured as private.
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
        return show(builder, false, () -> {});
    }

    AlertDialog show(AlertDialog.Builder builder, boolean sensitive, Runnable cleanup) {
        dismissDialog();
        AlertDialog shown = builder.create();
        shown.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog = shown;
        privateDialog = sensitive;
        shown.setOnDismissListener(ignored -> {
            cleanup.run();
            if (dialog == shown) {
                boolean wasEditing = editingDialog;
                dismissOverlay();
                dialog = null;
                privateDialog = false;
                boolean wasKept = keepDialog;
                editingDialog = false;
                saveBeforeBackground = null;
                keepDialog = false;
                if (wasEditing || wasKept) consumeImport();
            }
        });
        shown.show();
        return shown;
    }

    AlertDialog show(AlertDialog.Builder builder, boolean sensitive) {
        return show(builder, sensitive, () -> {});
    }

    void keepPreviewWhileBackgrounded() {
        keepDialog = true;
    }

    void keepEditorWhileBackgrounded(Runnable save) {
        editingDialog = true;
        saveBeforeBackground = save;
    }

    void showAbove(AlertDialog confirmation) {
        dismissOverlay();
        overlay = confirmation;
        confirmation.setOnDismissListener(ignored -> {
            if (overlay == confirmation) overlay = null;
        });
        confirmation.show();
    }

    private void dismissOverlay() {
        if (overlay != null) overlay.dismiss();
        overlay = null;
    }

    void dismissDialog() {
        dismissOverlay();
        if (dialog != null) dialog.dismiss();
        dialog = null;
        privateDialog = false;
        editingDialog = false;
        saveBeforeBackground = null;
        keepDialog = false;
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
        view.setMinimumWidth(dp(48));
        view.setMinWidth(dp(48));
        view.setPadding(0, dp(5), 0, dp(5));
        Drawable symbol = getDrawable(resource).mutate();
        symbol.setTint(accent);
        symbol.setBounds(0, 0, dp(19), dp(19));
        view.setCompoundDrawablesRelative(null, symbol, null, null);
        view.setCompoundDrawablePadding(dp(2));
        view.setLayoutParams(new LinearLayout.LayoutParams(0, dp(52), 1));
        return view;
    }

    ImageButton iconButton(int resource, String label, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setImageTintList(ColorStateList.valueOf(accent));
        button.setBackground(buttonBackground(Color.TRANSPARENT, 12));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    ImageButton filledIconButton(int resource, String label, Runnable action) {
        ImageButton button = iconButton(resource, label, action);
        button.setImageTintList(ColorStateList.valueOf(onAccent));
        button.setBackground(new android.graphics.drawable.InsetDrawable(buttonBackground(accent, 14), dp(4)));
        return button;
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
