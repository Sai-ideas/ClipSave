package dev.local.clipvault;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ContentEditor {
    final LinearLayout body;
    final EditText input;
    private final MainActivity activity;
    private final FrameLayout content;
    private final String original;
    private MarkdownPreview preview;
    private HorizontalScrollView markupStrip;
    private ImageButton mode;
    private boolean adjustingList;
    private Supplier<String> readingSource;
    private AlertDialog dialog;
    private boolean startReading;

    ContentEditor(MainActivity activity, String hint, String original, int limit, boolean multiline) {
        this.activity = activity;
        this.original = original;
        body = activity.dialogBody();
        body.setFocusableInTouchMode(true);
        input = activity.edit(hint, limit, multiline);
        input.setText(original);
        if (multiline) {
            input.setMaxLines(Integer.MAX_VALUE);
            input.setHorizontallyScrolling(false);
        }
        content = new FrameLayout(activity);
        content.addView(input, new FrameLayout.LayoutParams(-1, multiline ? -1 : -2));
        body.addView(content, new LinearLayout.LayoutParams(-1, multiline ? 0 : -2, multiline ? 1 : 0));
    }

    void enableReading(Supplier<String> source, boolean initiallyReading) {
        readingSource = source;
        startReading = initiallyReading;
        input.setTypeface(Typeface.MONOSPACE);

        LinearLayout toolbar = activity.row();
        markupStrip = new HorizontalScrollView(activity);
        markupStrip.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = activity.row();
        ImageButton headings = activity.iconButton(R.drawable.ic_md_heading, "標題", () -> {});
        headings.setOnClickListener(view -> showHeadings(headings));
        tools.addView(headings);
        addTool(tools, R.drawable.ic_md_bold, "粗體", "**", "**", "文字", "");
        addTool(tools, R.drawable.ic_md_italic, "斜體", "*", "*", "文字", "");
        addTool(tools, R.drawable.ic_md_bullets, "項目清單", "- ", "", "項目", "list");
        addTool(tools, R.drawable.ic_md_ordered, "編號清單", "1. ", "", "項目", "ordered");
        addTool(tools, R.drawable.ic_md_tasks, "待辦清單", "- [ ] ", "", "待辦事項", "task");
        addTool(tools, R.drawable.ic_md_quote, "引用", "> ", "", "引文", "quote");
        ImageButton code = activity.iconButton(R.drawable.ic_md_code, "程式碼", () -> {});
        code.setOnClickListener(view -> showCodeMenu(code));
        tools.addView(code);
        addTool(tools, R.drawable.ic_md_line, "分隔線", "\n---", "\n\n", "", "line");
        addTool(tools, R.drawable.ic_link, "連結", "[", "](https://example.com)", "連結文字", "");
        markupStrip.addView(tools);
        toolbar.addView(markupStrip, new LinearLayout.LayoutParams(0, activity.dp(48), 1));

        mode = activity.filledIconButton(R.drawable.ic_view, "預覽", () -> selectReading(preview == null));
        ((LinearLayout.LayoutParams) mode.getLayoutParams()).setMarginStart(activity.dp(4));
        toolbar.addView(mode);
        body.addView(toolbar, 0);

        input.addTextChangedListener(new TextWatcher() {
            private int newline;

            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}

            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                newline = !adjustingList && before == 0 && count == 1 && text.charAt(start) == '\n' ? start : -1;
            }

            @Override public void afterTextChanged(Editable text) {
                if (newline < 0 || adjustingList) return;
                MarkdownLists.Continuation next = MarkdownLists.afterEnter(text, newline);
                if (next == null || text.length() + next.text().length() - (next.end() - next.start()) > Vault.MAX_TEXT) return;
                adjustingList = true;
                try {
                    text.replace(next.start(), next.end(), next.text());
                    input.setSelection(next.start() + next.text().length());
                } finally {
                    adjustingList = false;
                }
            }
        });
    }

    private void addTool(LinearLayout tools, int icon, String name, String prefix, String suffix,
                         String placeholder, String kind) {
        tools.addView(activity.iconButton(icon, name, () -> insert(prefix, suffix, placeholder, kind)));
    }

    private void showHeadings(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        for (int level = 1; level <= 3; level++) {
            String prefix = new String[] {"# ", "## ", "### "}[level - 1];
            menu.getMenu().add(level + " 級標題").setOnMenuItemClickListener(item -> {
                insert(prefix, "", "標題", "line");
                return true;
            });
        }
        menu.show();
    }

    private void showCodeMenu(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add("行內程式碼").setOnMenuItemClickListener(item -> {
            insert("`", "`", "程式碼", "");
            return true;
        });
        menu.getMenu().add("程式碼區塊").setOnMenuItemClickListener(item -> {
            insert("```\n", "\n```\n", "程式碼", "line");
            return true;
        });
        menu.show();
    }

    private void selectReading(boolean reading) {
        if (!activity.canAct()) return;
        if (reading && preview == null) {
            activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
            preview = new MarkdownPreview(activity, activity::openLink);
            content.addView(preview, new FrameLayout.LayoutParams(-1, -1));
            preview.render(readingSource.get());
        } else if (!reading) {
            closePreview();
        }
        displayMode(reading);
    }

    private void displayMode(boolean reading) {
        input.setVisibility(reading ? View.GONE : View.VISIBLE);
        markupStrip.setVisibility(reading ? View.INVISIBLE : View.VISIBLE);
        mode.setImageResource(reading ? R.drawable.ic_edit : R.drawable.ic_view);
        mode.setContentDescription(reading ? "編輯" : "預覽");
        mode.setTooltipText(reading ? "編輯" : "預覽");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("存檔");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(reading ? View.GONE : View.VISIBLE);
    }

    private boolean dirty() {
        return !original.contentEquals(input.getText());
    }

    private void closePreview() {
        if (preview == null) return;
        content.removeView(preview);
        preview.destroy();
        preview = null;
    }

    private void insert(String prefix, String suffix, String placeholder, String kind) {
        if (!activity.canAct()) return;
        String linePrefix = prefix;
        int start = Math.min(input.getSelectionStart(), input.getSelectionEnd());
        int end = Math.max(input.getSelectionStart(), input.getSelectionEnd());
        if (start < 0) start = end = input.length();
        String selection = input.getText().subSequence(start, end).toString();
        String value = selection.isEmpty() ? placeholder : selection;
        if (!kind.isEmpty() && start > 0 && input.getText().charAt(start - 1) != '\n') {
            prefix = "\n" + prefix;
        }
        if (kind.equals("quote") || kind.equals("list") || kind.equals("task")) {
            value = value.replace("\n", "\n" + linePrefix);
        } else if (kind.equals("ordered")) {
            String[] lines = value.split("\n", -1);
            StringBuilder numbered = new StringBuilder(lines[0]);
            for (int index = 1; index < lines.length; index++) numbered.append('\n')
                    .append(index + 1).append(". ").append(lines[index]);
            value = numbered.toString();
        }
        input.requestFocus();
        input.getText().replace(start, end, prefix + value + suffix);
        int first = Math.min(start + prefix.length(), input.length());
        input.setSelection(first, Math.min(first + value.length(), input.length()));
    }

    void show(String title, boolean fullscreen, BooleanSupplier sensitive, Consumer<String> save) {
        show(title, fullscreen, sensitive, () -> false, save);
    }

    void show(String title, boolean fullscreen, BooleanSupplier sensitive,
              BooleanSupplier hasAlternativeContent, Consumer<String> save) {
        dialog = activity.show(new AlertDialog.Builder(activity).setTitle(title)
                .setView(fullscreen ? fillHeight(body) : body).setNegativeButton("取消", null)
                .setPositiveButton("儲存", null), sensitive.getAsBoolean(), this::closePreview);
        boolean markdown = readingSource != null;
        Runnable commit = () -> {
            if (!activity.canAct()) return;
            String value = input.getText().toString();
            if (markdown && (!dirty() || (original.isEmpty() && value.trim().isEmpty()))) {
                dialog.dismiss();
                return;
            }
            if (value.trim().isEmpty() && !hasAlternativeContent.getAsBoolean()) {
                if (preview != null) selectReading(false);
                input.setError("請輸入文字");
                return;
            }
            save.accept(value);
        };
        activity.keepEditorWhileBackgrounded(markdown ? () -> { if (dirty()) commit.run(); } : null);
        body.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (fullscreen) fullscreen(activity, dialog);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);

        Runnable cancel = () -> {
            if (!activity.canAct()) return;
            if (!dirty()) {
                dialog.dismiss();
            } else {
                activity.getSystemService(InputMethodManager.class)
                        .hideSoftInputFromWindow(input.getWindowToken(), 0);
                showDiscard(dialog);
            }
        };
        Runnable back = markdown ? commit : cancel;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> cancel.run());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> commit.run());
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back.run();
            return true;
        });
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedDispatcher dispatcher = dialog.getOnBackInvokedDispatcher();
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, back::run);
        }
        if (markdown) selectReading(startReading);
    }

    private void showDiscard(AlertDialog editor) {
        AlertDialog confirmation = new AlertDialog.Builder(activity).setTitle("放棄修改？")
                .setMessage("尚未儲存的內容會遺失。")
                .setNegativeButton("繼續編輯", null)
                .setPositiveButton("放棄修改", (ignored, which) -> editor.dismiss())
                .create();
        confirmation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        activity.showAbove(confirmation);
    }

    static FrameLayout fillHeight(View child) {
        FrameLayout frame = new FrameLayout(child.getContext()) {
            @Override protected void onMeasure(int width, int height) {
                // AlertDialog measures its custom panel at most the available height.
                if (MeasureSpec.getMode(height) == MeasureSpec.AT_MOST) {
                    height = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(height), MeasureSpec.EXACTLY);
                }
                super.onMeasure(width, height);
            }
        };
        frame.addView(child, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    static void fullscreen(MainActivity activity, AlertDialog dialog) {
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(activity.getColor(R.color.vault_background)));
        dialog.getWindow().setLayout(-1, -1);
    }
}
