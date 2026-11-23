package dev.local.clipvault;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

final class DraftWorkspace {
    static final int EXPORT_REQUEST = 14;

    private final MainActivity activity;
    private final Vault vault;
    private final int ink, muted, accent, surfaceSoft;
    private boolean active;
    private boolean reloadShelf;
    private Draft current;
    private Export export;
    private LinearLayout page;
    private record Export(String id, long updatedAt, boolean sensitive, Uri location) {}
    private record Drag(String draftId, int origin) {}

    DraftWorkspace(MainActivity activity, Vault vault) {
        this.activity = activity;
        this.vault = vault;
        ink = activity.getColor(R.color.vault_text);
        muted = activity.getColor(R.color.vault_secondary_text);
        accent = activity.getColor(R.color.vault_accent);
        surfaceSoft = activity.getColor(R.color.vault_surface_soft);
    }

    boolean active() { return active; }

    void deactivate() {
        active = false;
        current = null;
        page = null;
        reloadShelf = false;
    }

    void hidePrivateDraft() {
        if (current == null || !current.sensitive()) return;
        current = null;
        renderShelf(Collections.emptyList(), "讀取中…");
        reloadShelf = true;
    }

    void resumeShelf() {
        if (active && reloadShelf && activity.canAct()) openShelf();
    }

    boolean goBack() {
        if (!active) return false;
        if (current != null) openShelf();
        else activity.openClips();
        return true;
    }

    void openShelf() {
        if (!activity.canAct()) return;
        reloadShelf = false;
        renderShelf(Collections.emptyList(), "讀取中…");
        activity.run(vault::drafts, drafts -> renderShelf(drafts, "還沒有草稿。"),
                () -> renderShelf(Collections.emptyList(), "無法讀取草稿，請返回後重試。"));
    }

    private void renderShelf(List<Draft> drafts, String empty) {
        current = null;
        buildPage("文章草稿", "剪輯", activity::openClips);
        add(largeButton("新增草稿", () -> editText("新增草稿", "輸入文章標題", "", Draft.MAX_TITLE,
                false, title -> activity.runForeground(() -> vault.createDraft(title), this::renderDraft))), 12);
        if (drafts.isEmpty()) {
            add(activity.text(empty, 15, muted, false), 16);
            return;
        }
        for (Draft draft : drafts) {
            LinearLayout item = panel();
            item.addView(activity.text(displayName(draft), 18, ink, true));
            item.addView(activity.text(draft.sensitive() ? "驗證後開啟" : draft.blocks().size() + " 個區塊",
                    13, muted, false));
            item.addView(button("開啟", () -> open(draft)), activity.margins(-2, -2, 5, 0));
            add(item, 10);
        }
    }

    private void open(Draft draft) {
        activity.authorize(draft.sensitive(), "開啟私密草稿",
                () -> activity.runForeground(() -> {
                    Draft stored = vault.draft(draft.id());
                    if (stored.sensitive() && !draft.sensitive()) throw new Vault.Limit("請重新開啟私密草稿。");
                    return stored;
                }, this::renderDraft));
    }

    void pickDraft(Vault.Entry entry) {
        if (!activity.canAct()) return;
        activity.runForeground(vault::drafts, drafts -> {
            if (drafts.isEmpty()) {
                activity.confirm("還沒有草稿", "先建立一篇草稿，再插入這張卡片。", "前往草稿", this::openShelf);
                return;
            }
            activity.show(new AlertDialog.Builder(activity).setTitle("加入草稿")
                    .setItems(drafts.stream().map(DraftWorkspace::displayName).toArray(String[]::new),
                            (dialog, which) -> insert(drafts.get(which), entry))
                    .setNegativeButton("取消", null));
        });
    }

    private void pickClip(Draft draft) {
        activity.runForeground(vault::list, entries -> {
            ArrayList<Vault.Entry> available = new ArrayList<>();
            for (Vault.Entry entry : entries) {
                if (!entry.attachment() || Vault.supportsAttachment(entry.mime())) available.add(entry);
            }
            if (available.isEmpty()) {
                activity.toast("先收錄文字、網址或圖片，再插入草稿。");
                return;
            }
            String[] labels = available.stream().map(DraftWorkspace::clipLabel).toArray(String[]::new);
            activity.show(new AlertDialog.Builder(activity).setTitle("插入剪輯")
                    .setItems(labels, (dialog, which) -> insert(draft, available.get(which)))
                    .setNegativeButton("取消", null));
        });
    }

    private void insert(Draft draft, Vault.Entry entry) {
        boolean sensitive = draft.sensitive() || entry.sensitive();
        activity.authorize(sensitive, "插入私密剪輯", () -> activity.runForeground(
                () -> vault.addDraftClip(draft.id(), entry.id(), sensitive), this::renderDraft));
    }

    private void renderDraft(Draft draft) {
        ScrollView scroll = active && current != null && current.id().equals(draft.id()) && page != null
                ? (ScrollView) page.getParent() : null;
        int offset = scroll == null ? 0 : scroll.getScrollY();
        current = draft;
        buildPage(draft.title(), "草稿", this::openShelf);

        LinearLayout authoring = activity.row();
        addAction(authoring, "插入剪輯", () -> pickClip(draft));
        addAction(authoring, "新增段落", () -> addTextBlock(draft, Draft.Block.PARAGRAPH));
        addAction(authoring, "新增小標", () -> addTextBlock(draft, Draft.Block.HEADING));
        add(authoring, 10);

        if (draft.blocks().isEmpty()) {
            add(activity.text("插入素材或寫一段文字開始。", 15, muted, false), 16);
        } else {
            for (int index = 0; index < draft.blocks().size(); index++) {
                add(blockCard(draft, index), 10);
            }
        }
        LinearLayout output = activity.row();
        addAction(output, "預覽全文", () -> preview(draft));
        addAction(output, "複製全文", () -> copy(draft));
        addAction(output, "匯出 ZIP", () -> export(draft));
        add(output, 12);
        add(button("草稿設定", () -> draftOptions(draft)), 10);
        if (offset > 0) {
            View view = (View) page.getParent();
            view.post(() -> view.scrollTo(0, offset));
        }
    }

    private LinearLayout blockCard(Draft draft, int index) {
        Draft.Block block = draft.blocks().get(index);
        LinearLayout item = panel();
        String label = block.image() ? "圖片" : block.mime().equals(Draft.Block.HEADING) ? "小標"
                : Vault.isWebLink(block.content()) ? "網址" : "段落";
        item.addView(activity.text(label, 13, accent, true));
        TextView contents = activity.text(block.content(), 16, ink, false);
        contents.setMaxLines(4);
        contents.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(contents, activity.margins(-1, -2, 5, 4));

        LinearLayout actions = activity.row();
        if (!block.image()) addAction(actions, "編輯", () -> editBlock(draft, index));
        Button order = addAction(actions, "排序", () -> blockOrder(draft, index));
        order.setOnLongClickListener(view -> activity.canAct() && draft.blocks().size() > 1
                && view.startDragAndDrop(null, new View.DragShadowBuilder(view),
                new Drag(draft.id(), index), 0));
        addAction(actions, "移除", () -> removeBlock(draft, index));
        item.addView(actions);
        item.setOnDragListener((view, event) -> {
            if (!(event.getLocalState() instanceof Drag drag) || !drag.draftId().equals(draft.id())) {
                return false;
            }
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED -> item.setAlpha(0.65f);
                case DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> item.setAlpha(1f);
                case DragEvent.ACTION_DROP -> {
                    if (drag.origin() != index) moveBlock(draft, drag.origin(), index);
                }
                default -> {}
            }
            return true;
        });
        return item;
    }

    private void addTextBlock(Draft draft, String mime) {
        boolean heading = Draft.Block.HEADING.equals(mime);
        editText(heading ? "新增小標" : "新增段落", "輸入內容", "", Vault.MAX_TEXT, !heading, text -> {
            ArrayList<Draft.Block> blocks = new ArrayList<>(draft.blocks());
            blocks.add(Draft.Block.text(mime, text));
            update(draft, draft.title(), blocks);
        });
    }

    private void editBlock(Draft draft, int index) {
        Draft.Block block = draft.blocks().get(index);
        editText("編輯區塊", "輸入內容", block.content(), Vault.MAX_TEXT,
                !block.mime().equals(Draft.Block.HEADING), text -> {
                    ArrayList<Draft.Block> blocks = new ArrayList<>(draft.blocks());
                    blocks.set(index, block.withContent(text));
                    update(draft, draft.title(), blocks);
                });
    }

    private void blockOrder(Draft draft, int index) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Integer> targets = new ArrayList<>();
        if (index > 0) {
            labels.add("上移");
            targets.add(index - 1);
        }
        if (index < draft.blocks().size() - 1) {
            labels.add("下移");
            targets.add(index + 1);
        }
        if (labels.isEmpty()) return;
        activity.show(new AlertDialog.Builder(activity).setTitle("排序")
                .setItems(labels.toArray(new String[0]), (dialog, option) ->
                        moveBlock(draft, index, targets.get(option))).setNegativeButton("取消", null));
    }

    private void moveBlock(Draft draft, int from, int to) {
        ArrayList<Draft.Block> blocks = new ArrayList<>(draft.blocks());
        blocks.add(to, blocks.remove(from));
        update(draft, draft.title(), blocks);
    }

    private void removeBlock(Draft draft, int index) {
        activity.confirm("移除區塊", "這個區塊將從草稿移除。", "移除", () -> {
            ArrayList<Draft.Block> blocks = new ArrayList<>(draft.blocks());
            blocks.remove(index);
            update(draft, draft.title(), blocks);
        });
    }

    private void draftOptions(Draft draft) {
        activity.show(new AlertDialog.Builder(activity).setTitle("草稿設定")
                .setItems(new String[]{"重新命名", "刪除草稿"}, (dialog, which) -> {
                    if (which == 0) {
                        editText("重新命名", "輸入文章標題", draft.title(), Draft.MAX_TITLE, false,
                                title -> update(draft, title, draft.blocks()));
                    } else {
                        activity.confirm("刪除草稿", "這篇草稿及其區塊將從剪存移除。", "刪除",
                                () -> activity.runForeground(() -> {
                                    vault.deleteDraft(draft.id());
                                    return null;
                                }, ignored -> openShelf()));
                    }
                }).setNegativeButton("取消", null));
    }

    private void update(Draft draft, String title, List<Draft.Block> blocks) {
        activity.runForeground(() -> vault.updateDraft(draft, draft.withContent(title, blocks)), this::renderDraft);
    }

    private void preview(Draft draft) {
        activity.runForeground(() -> {
            HashMap<String, Bitmap> images = new HashMap<>();
            for (Draft.Block block : draft.blocks()) {
                if (!block.image() || images.containsKey(block.sourceId())) continue;
                byte[] bytes = vault.draftImage(draft, block);
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 480) {
                        options.inSampleSize *= 2;
                    }
                    options.inJustDecodeBounds = false;
                    images.put(block.sourceId(), BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options));
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                }
            }
            return images;
        }, images -> {
            ScrollView scroll = new ScrollView(activity);
            LinearLayout body = activity.dialogBody();
            scroll.addView(body);
            body.addView(activity.text(draft.title(), 22, ink, true));
            for (Draft.Block block : draft.blocks()) {
                Bitmap image = block.image() ? images.get(block.sourceId()) : null;
                if (image != null) {
                    ImageView view = new ImageView(activity);
                    view.setImageBitmap(image);
                    view.setAdjustViewBounds(true);
                    view.setContentDescription("草稿圖片");
                    body.addView(view, activity.margins(-1, -2, 12, 0));
                } else {
                    boolean heading = Draft.Block.HEADING.equals(block.mime());
                    body.addView(activity.text(block.content(), heading ? 19 : 16, ink, heading),
                            activity.margins(-1, -2, 12, 0));
                }
            }
            activity.show(new AlertDialog.Builder(activity).setTitle("文章預覽").setView(scroll)
                    .setPositiveButton("關閉", null));
        });
    }

    private void copy(Draft draft) {
        Runnable copy = () -> activity.copyArticle("剪存文章", Article.render(draft, false));
        if (draft.sensitive()) {
            activity.confirm("複製私密草稿", "全文會寫入系統剪貼簿，可貼到其他 App。", "複製", copy);
        } else {
            copy.run();
        }
    }

    private void export(Draft draft) {
        String description = draft.sensitive()
                ? "ZIP 內的私密文字與圖片可直接讀取。請確認你要把它匯出到選取的位置。"
                : "ZIP 內含文章的 Markdown 和圖片，可解壓後交給發布平台。";
        activity.confirm("匯出文章 ZIP", description, "選擇位置", () -> {
            if (!activity.canAct() || export != null) return;
            export = new Export(draft.id(), draft.updatedAt(), draft.sensitive(), null);
            Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "clipsave-article.zip");
            try {
                activity.startActivityForResult(picker, EXPORT_REQUEST);
            } catch (RuntimeException error) {
                cancelExport();
                activity.toast("無法開啟檔案選擇器。");
            }
        });
    }

    void exportResult(Uri location) {
        if (export == null || location == null || !"content".equals(location.getScheme())) {
            cancelExport();
            return;
        }
        export = new Export(export.id(), export.updatedAt(), export.sensitive(), location);
        resumeExport();
    }

    void cancelExport() { export = null; }

    void resumeExport() {
        if (export == null || export.location() == null || !activity.canAct()
                || !activity.hasWindowFocus()) return;
        Export selected = export;
        export = null;
        activity.run(() -> {
            Draft draft = vault.draft(selected.id());
            if (draft.updatedAt() != selected.updatedAt() || draft.sensitive() != selected.sensitive()) {
                throw new Vault.Limit("草稿已變更，請重新匯出。");
            }
            try (OutputStream output = activity.getContentResolver().openOutputStream(selected.location(), "wt")) {
                if (output == null) throw new Vault.Limit("無法寫入匯出位置。");
                Article.writeZip(vault, draft, output);
            }
            return null;
        }, ignored -> activity.toast("文章已匯出。"), null);
    }

    private static String displayName(Draft draft) {
        return draft.sensitive() ? "私密草稿" : draft.title();
    }

    private static String clipLabel(Vault.Entry entry) {
        if (entry.sensitive()) return entry.type() + " · 私密剪輯";
        String content = entry.content();
        return entry.type() + " · " + (content.length() > 70 ? content.substring(0, 70) + "…" : content);
    }

    private void editText(String title, String hint, String value, int limit, boolean multiline,
                          Consumer<String> done) {
        if (!activity.canAct()) return;
        LinearLayout body = activity.dialogBody();
        EditText input = activity.edit(hint, limit, multiline);
        input.setText(value);
        body.addView(input);
        AlertDialog dialog = activity.show(new AlertDialog.Builder(activity).setTitle(title).setView(body)
                .setPositiveButton("儲存", null).setNegativeButton("取消", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!activity.canAct()) return;
            String text = input.getText().toString();
            if (text.trim().isEmpty()) {
                input.setError("請輸入文字");
                return;
            }
            done.accept(multiline ? text : text.trim());
        });
    }

    private void buildPage(String title, String back, Runnable navigate) {
        active = true;
        page = activity.newPage();
        LinearLayout header = activity.row();
        header.addView(activity.text(title, 26, ink, true), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(button(back, navigate));
        page.addView(header);
    }

    private LinearLayout panel() {
        LinearLayout panel = activity.column();
        panel.setBackground(activity.panel(17));
        panel.setPadding(activity.dp(13), activity.dp(10), activity.dp(13), activity.dp(8));
        return panel;
    }

    private Button button(String label, Runnable task) {
        return activity.button(label, surfaceSoft, accent, () -> {
            if (activity.canAct()) task.run();
        });
    }

    private Button largeButton(String label, Runnable task) {
        return activity.button(label, accent, activity.getColor(R.color.vault_on_accent), () -> {
            if (activity.canAct()) task.run();
        });
    }

    private Button addAction(LinearLayout row, String label, Runnable task) {
        Button action = button(label, task);
        action.setPadding(activity.dp(3), 0, activity.dp(3), 0);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, activity.dp(48), 1);
        if (row.getChildCount() > 0) layout.setMarginStart(activity.dp(5));
        row.addView(action, layout);
        return action;
    }

    private void add(View view, int spacing) {
        page.addView(view, activity.margins(-1, -2, spacing, 0));
    }
}
