package dev.local.clipvault;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

final class DraftWorkspace {
    static final int EXPORT_REQUEST = 14;
    private static final int BLOCK_SPACING = 10;
    private static final String[] GROUP_COLORS = {"紫色", "藍色", "綠色", "琥珀色"};

    private final MainActivity activity;
    private final Vault vault;
    private final int ink, muted, accent, surfaceSoft, danger;
    private boolean active;
    private boolean reloadShelf;
    private Draft current;
    private Export export;
    private LinearLayout page;
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private boolean reading;
    private Map<String, Bitmap> readingImages = Collections.emptyMap();
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
        resetEditorMode();
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
        if (current != null && !selectedIds.isEmpty()) {
            if (activity.canAct()) {
                selectedIds.clear();
                renderDraft(current);
            }
        } else if (current != null) openShelf();
        else activity.openClips();
        return true;
    }

    void openShelf() {
        if (!activity.canAct()) return;
        reloadShelf = false;
        renderShelf(Collections.emptyList(), "讀取中…");
        activity.run(vault::drafts, drafts -> renderShelf(drafts, "還沒有拼報。"),
                () -> renderShelf(Collections.emptyList(), "無法讀取拼報，請返回後重試。"));
    }

    private void renderShelf(List<Draft> drafts, String empty) {
        current = null;
        resetEditorMode();
        buildPage("拼報", "剪輯", activity::openClips);
        add(largeButton("新增拼報", () -> editText("新增拼報", "輸入拼報標題", "", Draft.MAX_TITLE,
                false, title -> activity.runForeground(() -> vault.createDraft(title), this::renderDraft))), 12);
        if (drafts.isEmpty()) {
            add(activity.text(empty, 15, muted, false), 16);
            return;
        }
        for (Draft draft : drafts) {
            LinearLayout item = panel();
            item.addView(activity.text(draft.title(), 18, ink, true));
            item.addView(activity.text(draft.sensitive() ? "私密 · 驗證後開啟" : draft.blocks().size() + " 個區塊",
                    13, muted, false));
            LinearLayout actions = activity.row();
            addAction(actions, "開啟", () -> open(draft));
            addDeleteAction(actions, draft);
            item.addView(actions, activity.margins(-1, -2, 5, 0));
            add(item, 10);
        }
    }

    private void open(Draft draft) {
        activity.authorize(draft.sensitive(), "開啟私密拼報",
                () -> activity.runForeground(() -> {
                    Draft stored = vault.draft(draft.id());
                    if (stored.sensitive() && !draft.sensitive()) throw new Vault.Limit("請重新開啟私密拼報。");
                    return stored;
                }, this::renderDraft));
    }

    void pickDraft(Vault.Entry entry) {
        if (!activity.canAct()) return;
        activity.runForeground(vault::drafts, drafts -> {
            if (drafts.isEmpty()) {
                activity.confirm("還沒有拼報", "先建立一份拼報，再插入這張卡片。", "前往拼報", this::openShelf);
                return;
            }
            activity.show(new AlertDialog.Builder(activity).setTitle("加入拼報")
                    .setItems(drafts.stream().map(DraftWorkspace::displayName).toArray(String[]::new),
                            (dialog, which) -> insert(drafts.get(which), Collections.singletonList(entry)))
                    .setNegativeButton("取消", null));
        });
    }

    private void pickClip(Draft draft) {
        int remaining = Draft.MAX_BLOCKS - draft.blocks().size();
        if (remaining <= 0) {
            activity.toast("每份拼報最多 " + Draft.MAX_BLOCKS + " 個區塊。");
            return;
        }
        activity.runForeground(vault::list, entries -> {
            ArrayList<Vault.Entry> available = new ArrayList<>();
            for (Vault.Entry entry : entries) {
                if (!entry.attachment() || Vault.supportsAttachment(entry.mime())) available.add(entry);
            }
            if (available.isEmpty()) {
                activity.toast("先收錄文字、網址或圖片，再插入拼報。");
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
        boolean sameDraft = active && current != null && current.id().equals(draft.id()) && page != null;
        int offset = sameDraft ? ((ScrollView) page.getParent()).getScrollY() : 0;
        if (!sameDraft) resetEditorMode();
        active = true;
        reloadShelf = false;
        current = draft;
        page = activity.newPage(editorToolbar(draft));
        ScrollView scroll = (ScrollView) page.getParent();
        if (draft.blocks().isEmpty()) {
            add(activity.text("插入素材或寫一段文字開始。", 15, muted, false), 16);
        } else if (reading) {
            for (Draft.Block block : draft.blocks()) add(previewBlock(block, readingImages), 12);
        } else {
            renderCards(draft, scroll);
        }
        if (offset > 0) scroll.post(() -> scroll.scrollTo(0, offset));
    }

    private LinearLayout editorToolbar(Draft draft) {
        LinearLayout toolbar = activity.column();
        toolbar.setBackgroundColor(activity.getColor(R.color.vault_surface));
        toolbar.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4));

        LinearLayout header = activity.row();
        Button back = button("拼報", this::goBack);
        back.setContentDescription("返回拼報清單");
        header.addView(back);
        LinearLayout identity = activity.column();
        TextView title = activity.text(draft.title(), 20, ink, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAccessibilityHeading(true);
        identity.addView(title);
        if (draft.sensitive()) {
            String detail = draft.containsPrivateBlocks() ? "受私密卡片保護" : "已開啟手動保護";
            identity.addView(activity.text(detail, 12, muted, false));
        }
        LinearLayout.LayoutParams identityLayout = new LinearLayout.LayoutParams(0, -2, 1);
        identityLayout.setMarginStart(activity.dp(8));
        header.addView(identity, identityLayout);
        Button options = iconButton("拼報設定", R.drawable.ic_more, () -> editorOptions(draft));
        header.addView(options, new LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)));
        toolbar.addView(header);

        LinearLayout actions = activity.row();
        if (selectedIds.isEmpty()) {
            if (!reading) {
                addIcon(actions, "插入剪輯", R.drawable.ic_clip, () -> pickClip(draft));
                addIcon(actions, "新增區塊", R.drawable.ic_add_block, () -> chooseBlockKind(draft));
            }
            Button mode = addIcon(actions, reading ? "回到編輯" : "純內文",
                    reading ? R.drawable.ic_edit : R.drawable.ic_document, () -> toggleReading(draft));
            mode.setSelected(reading);
            addIcon(actions, "預覽全文", R.drawable.ic_view, () -> preview(draft));
            addIcon(actions, "複製全文", R.drawable.ic_copy, () -> copy(draft));
            addIcon(actions, "匯出 ZIP", R.drawable.ic_export, () -> export(draft));
        } else {
            TextView count = activity.text("已選 " + selectedIds.size() + " 張", 14, ink, true);
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            actions.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
            addIcon(actions, "退出多選", R.drawable.ic_close, () -> {
                selectedIds.clear();
                renderDraft(draft);
            });
            Button group = addIcon(actions, "串聯選取卡片", R.drawable.ic_group, () -> createGroup(draft));
            group.setEnabled(draft.canGroup(selectedIds));
            if (!group.isEnabled()) group.setAlpha(0.5f);
            addIcon(actions, "刪除選取卡片", R.drawable.ic_delete, () -> deleteSelected(draft));
        }
        HorizontalScrollView strip = new HorizontalScrollView(activity);
        strip.setFillViewport(true);
        strip.setHorizontalScrollBarEnabled(false);
        strip.addView(actions, new ScrollView.LayoutParams(-1, -2));
        toolbar.addView(strip);
        return toolbar;
    }

    private Button iconButton(String label, int icon, Runnable action) {
        Button result = button("", action);
        Drawable symbol = activity.getDrawable(icon).mutate();
        symbol.setTint(ink);
        int size = activity.dp(22);
        symbol.setBounds(0, 0, size, size);
        result.setCompoundDrawablesRelative(null, symbol, null, null);
        result.setGravity(Gravity.CENTER);
        result.setTextSize(0);
        result.setPadding(0, activity.dp(13), 0, 0);
        result.setContentDescription(label);
        result.setTooltipText(label);
        return result;
    }

    private Button addIcon(LinearLayout row, String label, int icon, Runnable action) {
        Button button = iconButton(label, icon, action);
        row.addView(button, new LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)));
        return button;
    }

    private void editorOptions(Draft draft) {
        String lock = draft.manualLock() ? "關閉手動保護" : "開啟手動保護";
        String status = draft.containsPrivateBlocks() ? "私密卡片會維持保護"
                : draft.manualLock() ? "開啟拼報時需要驗證" : "目前可直接開啟";
        activity.show(new AlertDialog.Builder(activity).setTitle("拼報設定 · " + status)
                .setItems(new String[]{lock, "重新命名", "刪除拼報"}, (dialog, which) -> {
                    if (which == 0) toggleManualLock(draft);
                    else if (which == 1) editText("重新命名", "輸入拼報標題", draft.title(), Draft.MAX_TITLE,
                            false, title -> update(draft, title, draft.blocks()));
                    else requestDelete(draft);
                }).setNegativeButton("取消", null));
    }

    private void toggleManualLock(Draft draft) {
        boolean enable = !draft.manualLock();
        String label = enable ? "開啟手動保護" : "關閉手動保護";
        activity.authorize(true, label, () -> saveDraft(draft, draft.withManualLock(enable)));
    }

    private void toggleReading(Draft draft) {
        if (reading) {
            resetEditorMode();
            renderDraft(draft);
        } else {
            activity.runForeground(() -> loadImages(draft), images -> {
                reading = true;
                readingImages = images;
                renderDraft(draft);
            });
        }
    }

    private void resetEditorMode() {
        reading = false;
        readingImages = Collections.emptyMap();
        selectedIds.clear();
    }

    private void renderCards(Draft draft, ScrollView scroll) {
        List<Draft.Section> sections = draft.sections();
        DraftReorder.routeEvents(scroll);
        BiConsumer<Integer, Integer> move =
                (from, to) -> saveDraft(draft, draft.moveSection(from, to));
        DraftReorder reorder = new DraftReorder(scroll, page, activity::canAct, move,
                activity.dp(BLOCK_SPACING), accent);
        for (int index = 0; index < sections.size(); index++) {
            int position = index;
            Draft.Section section = sections.get(index);
            Runnable order = () -> showOrder(position, sections.size(), to -> move.accept(position, to));
            View item = section.group() == null
                    ? blockCard(draft, section.start(), reorder, order)
                    : groupCard(draft, section, scroll, reorder, order);
            add(item, BLOCK_SPACING);
        }
    }

    private View groupCard(Draft draft, Draft.Section section, ScrollView scroll,
                           DraftReorder outer, Runnable order) {
        Draft.Group group = section.group();
        LinearLayout container = activity.column();
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(activity.getColor(R.color.vault_surface));
        frame.setCornerRadius(activity.dp(17));
        frame.setStroke(activity.dp(2), groupColor(group.color()));
        container.setBackground(frame);
        container.setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(8));

        LinearLayout header = activity.row();
        Button title = button(group.title(), order);
        title.setContentDescription("排序串聯 " + group.title() + "，拖曳可移動整組");
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = iconButton("串聯選項 " + group.title(), R.drawable.ic_more,
                () -> groupOptions(draft, group));
        header.addView(menu, new LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)));
        boolean editing = selectedIds.isEmpty();
        title.setEnabled(editing);
        menu.setEnabled(editing);
        if (editing) outer.addCard(container, title);
        container.addView(header);

        LinearLayout items = activity.column();
        items.setPadding(0, 0, 0, activity.dp(BLOCK_SPACING));
        DraftReorder inner = new DraftReorder(scroll, items, activity::canAct,
                (from, to) -> saveDraft(draft, draft.moveInside(section, from, to)),
                activity.dp(BLOCK_SPACING), accent);
        int count = section.end() - section.start();
        for (int index = 0; index < count; index++) {
            int from = index;
            View item = blockCard(draft, section.start() + index, inner, () ->
                    showOrder(from, count, to -> saveDraft(draft, draft.moveInside(section, from, to))));
            items.addView(item, activity.margins(-1, -2, BLOCK_SPACING, 0));
        }
        container.addView(items);
        return container;
    }

    private void toggleSelected(Draft draft, String id) {
        if (!activity.canAct()) return;
        if (!selectedIds.add(id)) selectedIds.remove(id);
        renderDraft(draft);
    }

    private void deleteSelected(Draft draft) {
        Set<String> selected = new LinkedHashSet<>(selectedIds);
        activity.confirm("刪除 " + selected.size() + " 張卡片", "所選卡片會從這份拼報移除，來源剪輯會保留。",
                "刪除", () -> {
                    ArrayList<Draft.Block> remaining = new ArrayList<>(draft.blocks());
                    remaining.removeIf(block -> selected.contains(block.id()));
                    saveSelectionChange(draft, draft.withContent(draft.title(), remaining));
                }, draft.sensitive());
    }

    private void createGroup(Draft draft) {
        Set<String> selected = new LinkedHashSet<>(selectedIds);
        if (!draft.canGroup(selected)) return;
        editText("建立串聯", "串聯標題", "串聯", Draft.MAX_TITLE, false,
                title -> saveSelectionChange(draft, draft.group(selected, title, 0)));
    }

    private void saveSelectionChange(Draft original, Draft changed) {
        activity.runForeground(() -> vault.updateDraft(original, changed), stored -> {
            selectedIds.clear();
            renderDraft(stored);
        });
    }

    private void groupOptions(Draft draft, Draft.Group group) {
        activity.show(new AlertDialog.Builder(activity).setTitle(group.title())
                .setItems(new String[]{"修改標題", "更改顏色", "解除串聯"}, (dialog, which) -> {
                    if (which == 0) {
                        editText("修改串聯標題", "串聯標題", group.title(), Draft.MAX_TITLE, false,
                                title -> saveDraft(draft, draft.updateGroup(group, title, group.color())));
                    } else if (which == 1) {
                        activity.show(new AlertDialog.Builder(activity).setTitle("串聯顏色")
                                .setSingleChoiceItems(GROUP_COLORS, colorIndex(group.color()), (colors, color) ->
                                        saveDraft(draft, draft.updateGroup(group, group.title(), color)))
                                .setNegativeButton("取消", null));
                    } else {
                        saveDraft(draft, draft.ungroup(group));
                    }
                }).setNegativeButton("取消", null));
    }

    private int colorIndex(int color) {
        return color >= 0 && color < GROUP_COLORS.length ? color : 0;
    }

    private int groupColor(int color) {
        return switch (colorIndex(color)) {
            case 1 -> activity.getColor(R.color.pinbao_group_blue);
            case 2 -> activity.getColor(R.color.pinbao_group_green);
            case 3 -> activity.getColor(R.color.pinbao_group_amber);
            default -> accent;
        };
    }

    private LinearLayout blockCard(Draft draft, int index, DraftReorder reorder, Runnable order) {
        Draft.Block block = draft.blocks().get(index);
        LinearLayout item = panel();
        boolean selecting = !selectedIds.isEmpty();
        item.setOnLongClickListener(view -> {
            toggleSelected(draft, block.id());
            return true;
        });
        if (selecting) {
            boolean selected = selectedIds.contains(block.id());
            item.setSelected(selected);
            item.setOnClickListener(view -> toggleSelected(draft, block.id()));
            item.addView(activity.text(selected ? "✓ 已選取" : "未選取", 13, accent, true));
        }
        Draft.Block.Kind kind = block.kind();
        String label = block.image() ? "圖片" : kind == Draft.Block.Kind.TEXT
                && Vault.isWebLink(block.content()) ? "網址" : kind.label;
        item.addView(activity.text(label, 13, accent, true));
        View content;
        if (block.image()) content = activity.text(block.content(), 16, ink, false);
        else if (!selecting && kind == Draft.Block.Kind.CHECKLIST) content = checklistBlock(draft, index);
        else content = textBlock(block);
        if (content instanceof TextView text) {
            text.setMaxLines(4);
            text.setEllipsize(TextUtils.TruncateAt.END);
        }
        item.addView(content, activity.margins(-1, -2, 5, 4));

        if (selecting) return item;
        LinearLayout actions = activity.row();
        Button handle = button("排序", order);
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
            box.setOnLongClickListener(view -> {
                toggleSelected(draft, draft.blocks().get(index).id());
                return true;
            });
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

    private void showOrder(int index, int count, IntConsumer move) {
        ArrayList<String> labels = new ArrayList<>();
        if (index > 0) labels.add("上移");
        if (index + 1 < count) labels.add("下移");
        if (labels.isEmpty()) return;
        activity.show(new AlertDialog.Builder(activity).setTitle("排序")
                .setItems(labels.toArray(new String[0]), (dialog, option) ->
                        move.accept(index + ("上移".equals(labels.get(option)) ? -1 : 1)))
                .setNegativeButton("取消", null));
    }

    private void removeBlock(Draft draft, int index) {
        activity.confirm("移除區塊", "這個區塊將從拼報移除。", "移除", () -> {
            ArrayList<Draft.Block> blocks = new ArrayList<>(draft.blocks());
            blocks.remove(index);
            update(draft, draft.title(), blocks);
        });
    }

    private void addDeleteAction(LinearLayout actions, Draft draft) {
        Button delete = addAction(actions, "刪除拼報", () -> requestDelete(draft));
        delete.setTextColor(danger);
        delete.setContentDescription("刪除拼報 " + displayName(draft));
    }

    private void requestDelete(Draft draft) {
        activity.authorize(draft.sensitive(), "驗證後刪除私密拼報", () -> activity.runForeground(() -> {
            Draft stored = vault.draft(draft.id());
            if (stored.sensitive() && !draft.sensitive()) {
                throw new Vault.Limit("拼報已改為私密，請重新開啟拼報清單。");
            }
            return new DeleteTarget(stored, vault.drafts());
        }, target -> {
            if (!active) renderShelf(target.shelf(), "還沒有拼報。");
            Draft stored = target.draft();
            activity.confirm("刪除拼報", "將永久刪除「" + stored.title() + "」和其中的區塊。來源剪輯會保留。",
                    "刪除拼報", () -> activity.run(() -> {
                        vault.deleteDraft(stored);
                        return vault.drafts();
                    }, drafts -> {
                        renderShelf(drafts, "還沒有拼報。");
                        activity.toast("已刪除拼報。");
                    }, null), stored.sensitive());
        }));
    }

    private void update(Draft draft, String title, List<Draft.Block> blocks) {
        saveDraft(draft, draft.withContent(title, blocks));
    }

    private void saveDraft(Draft original, Draft changed) {
        activity.runForeground(() -> vault.updateDraft(original, changed), this::renderDraft);
    }

    private Map<String, Bitmap> loadImages(Draft draft) throws Exception {
        Map<String, Bitmap> images = new HashMap<>();
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
    }

    private View previewBlock(Draft.Block block, Map<String, Bitmap> images) {
        if (!block.image()) return textBlock(block);
        Bitmap image = images.get(block.sourceId());
        if (image == null) return activity.text(block.content(), 16, ink, false);
        ImageView view = new ImageView(activity);
        view.setImageBitmap(image);
        view.setAdjustViewBounds(true);
        view.setContentDescription("拼報圖片");
        return view;
    }

    private void preview(Draft draft) {
        activity.runForeground(() -> loadImages(draft), images -> {
            ScrollView scroll = new ScrollView(activity);
            LinearLayout body = activity.dialogBody();
            scroll.addView(body);
            body.addView(activity.text(draft.title(), 22, ink, true));
            for (Draft.Block block : draft.blocks()) {
                body.addView(previewBlock(block, images), activity.margins(-1, -2, 12, 0));
            }
            activity.show(new AlertDialog.Builder(activity).setTitle("文章預覽").setView(scroll)
                    .setPositiveButton("關閉", null));
        });
    }

    private void copy(Draft draft) {
        Runnable copy = () -> activity.copyArticle("剪存文章", Article.render(draft, false));
        if (draft.sensitive()) {
            activity.confirm("複製私密拼報", "全文會寫入系統剪貼簿，可貼到其他 App。", "複製", copy);
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
                throw new Vault.Limit("拼報已變更，請重新匯出。");
            }
            try (OutputStream output = activity.getContentResolver().openOutputStream(selected.location(), "wt")) {
                if (output == null) throw new Vault.Limit("無法寫入匯出位置。");
                Article.writeZip(vault, draft, output);
            }
            return null;
        }, ignored -> activity.toast("文章已匯出。"), null);
    }

    private static String displayName(Draft draft) {
        return draft.sensitive() ? draft.title() + " · 私密" : draft.title();
    }

    private static String clipLabel(Vault.Entry entry) {
        if (entry.sensitive()) return entry.type() + " · 私密 · " + entry.privateTitle();
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
