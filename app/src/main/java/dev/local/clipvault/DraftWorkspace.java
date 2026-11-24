package dev.local.clipvault;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

final class DraftWorkspace {
    static final int EXPORT_REQUEST = 14;

    private final MainActivity activity;
    private final Vault vault;
    private final int ink, muted, accent, surfaceSoft, danger;
    private boolean active;
    private boolean reloadShelf;
    private Draft current;
    private Export export;
    private LinearLayout page;
    private record Export(String id, long updatedAt, boolean sensitive, Uri location) {}
    private record DeleteTarget(Draft draft, List<Draft> shelf) {}

    DraftWorkspace(MainActivity activity, Vault vault) {
        this.activity = activity;
        this.vault = vault;
        ink = activity.getColor(R.color.vault_text);
        muted = activity.getColor(R.color.vault_secondary_text);
        accent = activity.getColor(R.color.vault_accent);
        surfaceSoft = activity.getColor(R.color.vault_surface_soft);
        danger = activity.getColor(R.color.vault_danger);
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
            LinearLayout actions = activity.row();
            addAction(actions, "開啟", () -> open(draft));
            addDeleteAction(actions, draft);
            item.addView(actions, activity.margins(-1, -2, 5, 0));
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
                            (dialog, which) -> insert(drafts.get(which), Collections.singletonList(entry)))
                    .setNegativeButton("取消", null));
        });
    }

    private void pickClip(Draft draft) {
        int remaining = Draft.MAX_BLOCKS - draft.blocks().size();
        if (remaining <= 0) {
            activity.toast("每篇草稿最多 " + Draft.MAX_BLOCKS + " 個區塊。");
            return;
        }
        activity.runForeground(vault::list, entries -> {
            ArrayList<Vault.Entry> available = new ArrayList<>();
            for (Vault.Entry entry : entries) {
                if (!entry.attachment() || Vault.supportsAttachment(entry.mime())) available.add(entry);
            }
            if (available.isEmpty()) {
                activity.toast("先收錄文字、網址或圖片，再插入草稿。");
                return;
            }
            showClipSelection(draft, available, remaining);
        });
    }

    private void showClipSelection(Draft draft, List<Vault.Entry> available, int remaining) {
        ArrayList<String> labels = new ArrayList<>(available.size());
        for (Vault.Entry entry : available) labels.add(clipLabel(entry));
        ArrayList<String> rows = new ArrayList<>(labels);
        LinkedHashSet<Integer> selection = new LinkedHashSet<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_list_item_multiple_choice, rows);
        AlertDialog dialog = activity.show(new AlertDialog.Builder(activity)
                .setTitle("插入剪輯")
                .setAdapter(adapter, null)
                .setNegativeButton("取消", null)
                .setPositiveButton("插入 0 筆", null));
        ListView list = dialog.getListView();
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        confirm.setEnabled(false);
        list.setOnItemClickListener((parent, row, position, id) -> {
            if (list.isItemChecked(position)) selection.add(position);
            else selection.remove(position);

            for (int index = 0; index < labels.size(); index++) rows.set(index, labels.get(index));
            int order = 1;
            for (int selected : selection) {
                rows.set(selected, order++ + ". " + labels.get(selected));
            }
            adapter.notifyDataSetChanged();

            int count = selection.size();
            dialog.setTitle(count > remaining ? "最多可插入 " + remaining + " 筆" : "插入剪輯");
            confirm.setText("插入 " + count + " 筆");
            confirm.setEnabled(count > 0 && count <= remaining);
        });
        confirm.setOnClickListener(view -> {
            if (!activity.canAct() || !confirm.isEnabled()) return;
            ArrayList<Vault.Entry> selected = new ArrayList<>(selection.size());
            for (int index : selection) selected.add(available.get(index));
            dialog.dismiss();
            insert(draft, selected);
        });
    }

    private void insert(Draft draft, List<Vault.Entry> selected) {
        boolean sensitive = draft.sensitive() || selected.stream().anyMatch(Vault.Entry::sensitive);
        ArrayList<String> ids = new ArrayList<>(selected.size());
        for (Vault.Entry entry : selected) ids.add(entry.id());
        activity.authorize(sensitive, "插入私密剪輯", () -> activity.runForeground(
                () -> vault.addDraftClips(draft.id(), ids, sensitive), this::renderDraft));
    }

    private void renderDraft(Draft draft) {
        ScrollView scroll = active && current != null && current.id().equals(draft.id()) && page != null
                ? (ScrollView) page.getParent() : null;
        int offset = scroll == null ? 0 : scroll.getScrollY();
        current = draft;
        buildPage(draft.title(), "草稿", this::openShelf);

        LinearLayout management = activity.row();
        addAction(management, "重新命名", () -> editText("重新命名", "輸入文章標題", draft.title(),
                Draft.MAX_TITLE, false, title -> update(draft, title, draft.blocks())));
        addDeleteAction(management, draft);
        add(management, 10);

        LinearLayout authoring = activity.row();
        addAction(authoring, "插入剪輯", () -> pickClip(draft));
        addAction(authoring, "新增區塊", () -> chooseBlockKind(draft));
        add(authoring, 10);

        DraftReorder reorder = new DraftReorder((ScrollView) page.getParent(), activity::canAct,
                (from, to) -> moveBlock(draft, from, to), activity.dp(60), activity.dp(9));
        if (draft.blocks().isEmpty()) {
            add(activity.text("插入素材或寫一段文字開始。", 15, muted, false), 16);
        } else {
            for (int index = 0; index < draft.blocks().size(); index++) {
                add(blockCard(draft, index, reorder), 10);
            }
        }
        LinearLayout output = activity.row();
        addAction(output, "預覽全文", () -> preview(draft));
        addAction(output, "複製全文", () -> copy(draft));
        addAction(output, "匯出 ZIP", () -> export(draft));
        add(output, 12);
        if (offset > 0) {
            View view = (View) page.getParent();
            view.post(() -> view.scrollTo(0, offset));
        }
    }

    private LinearLayout blockCard(Draft draft, int index, DraftReorder reorder) {
        Draft.Block block = draft.blocks().get(index);
        LinearLayout item = panel();
        Draft.Block.Kind kind = block.kind();
        String label = block.image() ? "圖片" : kind == Draft.Block.Kind.TEXT
                && Vault.isWebLink(block.content()) ? "網址" : kind.label;
        item.addView(activity.text(label, 13, accent, true));
        View content;
        if (block.image()) content = activity.text(block.content(), 16, ink, false);
        else if (kind == Draft.Block.Kind.CHECKLIST) content = checklistBlock(draft, index);
        else content = textBlock(block);
        if (content instanceof TextView text) {
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
        }
        item.addView(content, activity.margins(-1, -2, 5, 4));

        LinearLayout actions = activity.row();
        Button handle = button("排序", () -> blockOrder(draft, index));
        handle.setMinWidth(activity.dp(64));
        handle.setMinimumWidth(activity.dp(64));
        Drawable icon = activity.getDrawable(R.drawable.ic_reorder).mutate();
        icon.setTint(accent);
        handle.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null);
        handle.setCompoundDrawablePadding(activity.dp(2));
        handle.setContentDescription("排序，拖曳可移動區塊，點按可上移或下移");
        actions.addView(handle, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                activity.dp(48)));
        reorder.addCard(item, handle);
        if (!block.image() && kind != Draft.Block.Kind.DIVIDER) {
            addAction(actions, "編輯", () -> editBlock(draft, index));
        }
        addAction(actions, "移除", () -> removeBlock(draft, index));
        item.addView(actions);
        return item;
    }

    private void chooseBlockKind(Draft draft) {
        Draft.Block.Kind[] kinds = Draft.Block.Kind.values();
        String[] labels = new String[kinds.length];
        for (int index = 0; index < kinds.length; index++) labels[index] = kinds[index].label;
        activity.show(new AlertDialog.Builder(activity).setTitle("新增區塊")
                .setItems(labels, (dialog, which) -> addBlock(draft, kinds[which]))
                .setNegativeButton("取消", null));
    }

    private void addBlock(Draft draft, Draft.Block.Kind kind) {
        Consumer<String> save = text -> {
            ArrayList<Draft.Block> blocks = new ArrayList<>(draft.blocks());
            blocks.add(Draft.Block.text(kind.mime, text));
            update(draft, draft.title(), blocks);
        };
        if (kind == Draft.Block.Kind.DIVIDER) save.accept("");
        else editBlockText(kind.label, kind, "", save);
    }

    private void editBlock(Draft draft, int index) {
        Draft.Block block = draft.blocks().get(index);
        Draft.Block.Kind kind = block.kind();
        editBlockText("編輯區塊", kind, block.content(), text -> replaceBlock(draft, index, text));
    }

    private void editBlockText(String title, Draft.Block.Kind kind, String content, Consumer<String> save) {
        if (kind == Draft.Block.Kind.CHECKLIST) {
            editText("待辦清單", kind.hint, content, Vault.MAX_TEXT, true, Checklist::normalize, save);
        } else {
            editText(title, kind.hint, content, Vault.MAX_TEXT, kind.multiline, save);
        }
    }

    private View checklistBlock(Draft draft, int index) {
        LinearLayout list = activity.column();
        List<Checklist.Item> items = Checklist.items(draft.blocks().get(index).content());
        for (int position = 0; position < items.size(); position++) {
            int itemIndex = position;
            Checklist.Item item = items.get(position);
            CheckBox box = activity.checkbox(item.text(), item.checked());
            box.setTextColor(ink);
            box.setTextSize(16);
            box.setOnClickListener(view -> {
                if (!activity.canAct()) {
                    box.setChecked(item.checked());
                    return;
                }
                ArrayList<Checklist.Item> changed = new ArrayList<>(items);
                changed.set(itemIndex, new Checklist.Item(item.text(), box.isChecked()));
                replaceBlock(draft, index, Checklist.render(changed, true), () -> box.setChecked(item.checked()));
            });
            list.addView(box, new LinearLayout.LayoutParams(-1, -2));
        }
        return list;
    }

    private void replaceBlock(Draft draft, int index, String content) {
        replaceBlock(draft, index, content, null);
    }

    private void replaceBlock(Draft draft, int index, String content, Runnable failure) {
        ArrayList<Draft.Block> blocks = new ArrayList<>(draft.blocks());
        blocks.set(index, blocks.get(index).withContent(content));
        activity.runForeground(() -> vault.updateDraft(draft, draft.withContent(draft.title(), blocks)),
                this::renderDraft, failure);
    }

    private View textBlock(Draft.Block block) {
        Draft.Block.Kind kind = block.kind();
        if (kind == Draft.Block.Kind.DIVIDER) {
            View line = new View(activity);
            line.setMinimumHeight(activity.dp(2));
            line.setBackgroundColor(muted);
            line.setContentDescription("分隔線");
            return line;
        }
        boolean heading = kind == Draft.Block.Kind.SUBHEADING || kind == Draft.Block.Kind.TITLE;
        TextView text = activity.text(Article.formatText(block, false),
                kind == Draft.Block.Kind.TITLE ? 22 : heading ? 19 : 16, ink, heading);
        if (kind == Draft.Block.Kind.CODE) text.setTypeface(Typeface.MONOSPACE);
        if (kind == Draft.Block.Kind.QUOTE || kind == Draft.Block.Kind.CODE) {
            text.setBackgroundColor(surfaceSoft);
            text.setPadding(activity.dp(9), activity.dp(6), activity.dp(9), activity.dp(6));
        }
        return text;
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

    private void addDeleteAction(LinearLayout actions, Draft draft) {
        Button delete = addAction(actions, "刪除草稿", () -> requestDelete(draft));
        delete.setTextColor(danger);
        delete.setContentDescription("刪除草稿 " + displayName(draft));
    }

    private void requestDelete(Draft draft) {
        activity.authorize(draft.sensitive(), "驗證後刪除私密草稿", () -> activity.runForeground(() -> {
            Draft stored = vault.draft(draft.id());
            if (stored.sensitive() && !draft.sensitive()) {
                throw new Vault.Limit("草稿已改為私密，請重新開啟草稿清單。");
            }
            return new DeleteTarget(stored, vault.drafts());
        }, target -> {
            if (!active) renderShelf(target.shelf(), "還沒有草稿。");
            Draft stored = target.draft();
            activity.confirm("刪除草稿", "將永久刪除「" + stored.title() + "」和其中的區塊。來源剪輯會保留。",
                    "刪除草稿", () -> activity.run(() -> {
                        vault.deleteDraft(stored);
                        return vault.drafts();
                    }, drafts -> {
                        renderShelf(drafts, "還沒有草稿。");
                        activity.toast("已刪除草稿。");
                    }, null), stored.sensitive());
        }));
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
                    View text = block.image() ? activity.text(block.content(), 16, ink, false) : textBlock(block);
                    body.addView(text, activity.margins(-1, -2, 12, 0));
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
        editText(title, hint, value, limit, multiline, Function.identity(), done);
    }

    private void editText(String title, String hint, String value, int limit, boolean multiline,
                          Function<String, String> prepare, Consumer<String> done) {
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
            String prepared;
            try {
                prepared = prepare.apply(multiline ? text : text.trim());
            } catch (IllegalArgumentException error) {
                input.setError(error.getMessage());
                return;
            }
            done.accept(prepared);
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
