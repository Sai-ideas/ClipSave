package dev.local.clipvault;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class ContentEditor {
    final LinearLayout body;
    final EditText input;
    private final MainActivity activity;
    private final FrameLayout content;
    private final String original;
    private MarkdownPreview preview;
    private Button previewButton;

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

    void enableMarkdown() {
        input.setTypeface(Typeface.MONOSPACE);
        LinearLayout toolbar = activity.row();
        Button markup = tool("插入語法", () -> {});
        markup.setOnClickListener(view -> showMarkupMenu(markup));
        previewButton = tool("Markdown 預覽", this::togglePreview);
        toolbar.addView(markup, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(previewButton, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(toolbar, 0);
    }

    private Button tool(String label, Runnable action) {
        return activity.button(label, activity.getColor(R.color.vault_surface_soft),
                activity.getColor(R.color.vault_accent), action);
    }

    private void togglePreview() {
        if (preview != null) {
            closePreview();
            input.setVisibility(View.VISIBLE);
            previewButton.setText("Markdown 預覽");
            return;
        }
        activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        preview = new MarkdownPreview(activity);
        content.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        preview.render(input.getText().toString());
        input.setVisibility(View.GONE);
        previewButton.setText("返回編輯");
    }

    private void closePreview() {
        if (preview == null) return;
        content.removeView(preview);
        preview.destroy();
        preview = null;
    }

    private void showMarkupMenu(View anchor) {
        if (preview != null) togglePreview();
        PopupMenu menu = new PopupMenu(activity, anchor);
        String[][] snippets = {
            {"標題", "# ", "", "標題", "line"},
            {"粗體", "**", "**", "文字", ""},
            {"斜體", "*", "*", "文字", ""},
            {"引用", "> ", "", "引文", "quote"},
            {"項目清單", "- ", "", "項目", "list"},
            {"編號清單", "1. ", "", "項目", "ordered"},
            {"待辦清單", "- [ ] ", "", "待辦事項", "task"},
            {"行內程式碼", "`", "`", "程式碼", ""},
            {"程式碼區塊", "```\n", "\n```\n", "程式碼", "line"},
            {"分隔線", "\n---", "\n\n", "", "line"},
            {"連結", "[", "](https://example.com)", "連結文字", ""}
        };
        for (String[] snippet : snippets) {
            menu.getMenu().add(snippet[0]).setOnMenuItemClickListener(item -> {
                insert(snippet);
                return true;
            });
        }
        menu.show();
    }

    private void insert(String[] snippet) {
        int start = Math.min(input.getSelectionStart(), input.getSelectionEnd());
        int end = Math.max(input.getSelectionStart(), input.getSelectionEnd());
        if (start < 0) start = end = input.length();
        String selection = input.getText().subSequence(start, end).toString();
        String value = selection.isEmpty() ? snippet[3] : selection;
        String prefix = snippet[1];
        if (!snippet[4].isEmpty() && start > 0 && input.getText().charAt(start - 1) != '\n') {
            prefix = "\n" + prefix;
        }
        if (snippet[4].equals("quote") || snippet[4].equals("list") || snippet[4].equals("task")) {
            value = value.replace("\n", "\n" + snippet[1]);
        } else if (snippet[4].equals("ordered")) {
            String[] lines = value.split("\n", -1);
            StringBuilder numbered = new StringBuilder(lines[0]);
            for (int index = 1; index < lines.length; index++) numbered.append('\n')
                    .append(index + 1).append(". ").append(lines[index]);
            value = numbered.toString();
        }
        input.requestFocus();
        input.getText().replace(start, end, prefix + value + snippet[2]);
        int first = Math.min(start + prefix.length(), input.length());
        input.setSelection(first, Math.min(first + value.length(), input.length()));
    }

    void show(String title, boolean fullscreen, BooleanSupplier sensitive, BooleanSupplier optionsChanged,
              Consumer<String> save) {
        AlertDialog dialog = activity.show(new AlertDialog.Builder(activity)
                .setTitle(title).setView(fullscreen ? fillHeight(body) : body).setNegativeButton("取消", null).setPositiveButton("儲存", null),
                sensitive.getAsBoolean(), this::closePreview);
        activity.keepEditorWhileBackgrounded();
        body.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (fullscreen) fullscreen(activity, dialog);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setCancelable(false);

        Runnable cancel = () -> {
            if (!activity.canAct()) return;
            if (!original.contentEquals(input.getText()) || optionsChanged.getAsBoolean()) {
                activity.getSystemService(InputMethodManager.class)
                        .hideSoftInputFromWindow(input.getWindowToken(), 0);
                showDiscard(dialog);
            } else {
                dialog.dismiss();
            }
        };
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> cancel.run());
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) cancel.run();
            return true;
        });
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedDispatcher back = dialog.getOnBackInvokedDispatcher();
            back.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    cancel::run);
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!activity.canAct()) return;
            String value = input.getText().toString();
            if (value.trim().isEmpty()) {
                if (preview != null) togglePreview();
                input.setError("請輸入文字");
                return;
            }
            save.accept(value);
        });
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
