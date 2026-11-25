package dev.local.clipvault;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.StaticLayout;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
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
import java.util.function.IntFunction;
import java.util.function.Consumer;
import java.util.function.Function;

final class DraftWorkspace {
    static final int EXPORT_REQUEST = 14;
    private static final int BLOCK_SPACING = 10;

    private final MainActivity activity;
    private final Vault vault;
    private final int ink, muted, accent, surfaceSoft, danger;
    private boolean active;
    private boolean reloadShelf;
    private Draft current;
    private Export export;
    private LinearLayout page;
    private LinearLayout editorBar;
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private boolean reading;
    private Map<String, Bitmap> cardImages = Collections.emptyMap();
    private Set<String> sourceDifferences = Collections.emptySet();
    private Position remembered;
    private record Position(String draftId, int scrollY, boolean reading) {}
    private record Opened(Draft draft, Map<String, Bitmap> images, Set<String> sourceDifferences) {}
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
        editorBar = null;
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

    void switchToClips() {
        remembered = current == null ? null : new Position(current.id(),
                ((ScrollView) page.getParent()).getScrollY(), reading);
        activity.openClips();
    }

    void switchToPinbao() {
        Position position = remembered;
        if (position == null) {
            openShelf();
            return;
        }
        activity.runForeground(() -> {
            for (Draft draft : vault.drafts()) {
                if (draft.id().equals(position.draftId())) return draft;
            }
            return null;
        }, draft -> {
            if (draft == null) openShelf();
            else open(draft, position);
        });
    }

    void openShelf() {
        if (!activity.canAct()) return;
        remembered = null;
        reloadShelf = false;
        renderShelf(Collections.emptyList(), "讀取中…");
        activity.run(vault::drafts, drafts -> renderShelf(drafts, "還沒有拼報。"),
                () -> renderShelf(Collections.emptyList(), "無法讀取拼報，請返回後重試。"));
    }

    private void renderShelf(List<Draft> drafts, String empty) {
        current = null;
        editorBar = null;
        resetEditorMode();
        active = true;
        page = activity.newPage(null, true);
        TextView heading = activity.text("拼報", 26, ink, true);
        heading.setAccessibilityHeading(true);
        page.addView(heading);
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
        open(draft, null);
    }

    private void open(Draft draft, Position position) {
        activity.authorize(draft.sensitive(), "開啟私密拼報",
                () -> activity.runForeground(() -> {
                    Draft stored = vault.draft(draft.id());
                    if (stored.sensitive() && !draft.sensitive()) throw new Vault.Limit("請重新開啟私密拼報。");
                    return editorData(stored, loadImages(stored, Collections.emptyMap(), true));
                }, opened -> renderDraft(opened, position)));
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
        DraftSheet sheet = new DraftSheet(activity, "插入剪輯", "插入 0 筆", draft.sensitive());
        LinkedHashSet<Integer> selection = new LinkedHashSet<>();
        IntFunction<String> heading = index -> {
            int order = 1;
            for (int selected : selection) {
                if (selected == index) return order + " · " + available.get(index).type();
                order++;
            }
            return available.get(index).type();
        };
        ListView list = pickerCards(available.size(), heading, index -> clipLabel(available.get(index)),
                selection::contains);
        sheet.body.addView(list, new LinearLayout.LayoutParams(-1, -1));
        Button insert = sheet.apply;
        insert.setEnabled(false);
        list.setOnItemClickListener((parent, row, index, id) -> {
            if (!selection.add(index)) selection.remove(index);
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
            int count = selection.size();
            insert.setText(count > remaining ? "最多可插入 " + remaining + " 筆" : "插入 " + count + " 筆");
            insert.setEnabled(count > 0 && count <= remaining);
        });
        insert.setOnClickListener(view -> {
            if (!activity.canAct() || !insert.isEnabled()) return;
            ArrayList<Vault.Entry> selected = new ArrayList<>(selection.size());
            for (int index : selection) selected.add(available.get(index));
            sheet.dismiss();
            insert(draft, selected);
        });
    }

    private record PickerRow(LinearLayout frame, LinearLayout card, TextView title, TextView subtitle) {}

    private ListView pickerCards(int count, IntFunction<String> titles, IntFunction<String> subtitles,
                                 java.util.function.IntPredicate selected) {
        ListView list = new ListView(activity);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return count; }
            @Override public Object getItem(int index) { return titles.apply(index); }
            @Override public long getItemId(int index) { return index; }

            @Override public View getView(int index, View recycled, ViewGroup parent) {
                PickerRow row;
                if (recycled == null) {
                    LinearLayout frame = activity.column();
                    frame.setPadding(0, 0, 0, activity.dp(6));
                    LinearLayout card = panel();
                    card.setMinimumHeight(activity.dp(48));
                    TextView title = activity.text("", 15, ink, true);
                    TextView subtitle = activity.text("", 15, muted, false);
                    subtitle.setMaxLines(3);
                    subtitle.setEllipsize(TextUtils.TruncateAt.END);
                    card.addView(title);
                    card.addView(subtitle, activity.margins(-1, -2, 4, 0));
                    frame.addView(card);
                    row = new PickerRow(frame, card, title, subtitle);
                    frame.setTag(row);
                } else row = (PickerRow) recycled.getTag();
                boolean checked = selected.test(index);
                row.title().setText(titles.apply(index));
                String subtitle = subtitles.apply(index);
                row.subtitle().setText(subtitle);
                row.subtitle().setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
                GradientDrawable surface = activity.panel(17);
                if (checked) {
                    surface.setColor(surfaceSoft);
                    surface.setStroke(activity.dp(1), accent);
                }
                row.card().setBackground(surface);
                row.frame().setSelected(checked);
                return row.frame();
            }
        });
        return list;
    }

    private void insert(Draft draft, List<Vault.Entry> selected) {
        boolean sensitive = draft.sensitive() || selected.stream().anyMatch(Vault.Entry::sensitive);
        ArrayList<String> ids = new ArrayList<>(selected.size());
        for (Vault.Entry entry : selected) ids.add(entry.id());
        activity.authorize(sensitive, "插入私密剪輯", () -> {
            Map<String, Bitmap> cached = current != null && current.id().equals(draft.id())
                    ? cardImages : Collections.emptyMap();
            activity.runForeground(() -> {
                Draft stored = vault.addDraftClips(draft.id(), ids, sensitive);
                return editorData(stored, loadImages(stored, cached, true));
            }, this::renderDraft);
        });
    }

    private Opened editorData(Draft draft, Map<String, Bitmap> images) throws Exception {
        return new Opened(draft, images, vault.draftSourceDifferences(draft));
    }

    private void renderDraft(Opened opened) {
        renderDraft(opened, null);
    }

    private void renderDraft(Opened opened, Position position) {
        renderDraft(opened.draft(), position, opened.images(), opened.sourceDifferences());
    }

    private void renderDraft(Draft draft) {
        renderDraft(draft, null, null, null);
    }

    private void renderDraft(Draft draft, Position position, Map<String, Bitmap> images, Set<String> differences) {
        boolean sameDraft = active && current != null && current.id().equals(draft.id()) && page != null;
        int offset = position != null ? position.scrollY()
                : sameDraft ? ((ScrollView) page.getParent()).getScrollY() : 0;
        if (!sameDraft) resetEditorMode();
        if (position != null) reading = position.reading();
        if (images != null) cardImages = images;
        if (differences != null) sourceDifferences = differences;
        if (!cardImages.isEmpty()) {
            HashMap<String, Bitmap> retained = new HashMap<>();
            for (Draft.Block block : draft.blocks()) {
                if (block.image() && cardImages.containsKey(block.sourceId())) {
                    retained.put(block.sourceId(), cardImages.get(block.sourceId()));
                }
            }
            cardImages = retained;
        }
        active = true;
        reloadShelf = false;
        current = draft;
        if (sameDraft) {
            activity.dismissDialog();
            editorBar.removeAllViews();
            page.removeAllViews();
        } else {
            editorBar = activity.column();
            editorBar.setBackgroundColor(activity.getColor(R.color.vault_surface));
            editorBar.setPadding(activity.dp(8), activity.dp(4), activity.dp(8), activity.dp(4));
            page = activity.newPage(editorBar, true);
        }
        bindEditorToolbar(draft);
        ScrollView scroll = (ScrollView) page.getParent();
        if (draft.blocks().isEmpty()) {
            add(activity.text("插入素材或寫一段文字開始。", 15, muted, false), 16);
        } else {
            renderCards(draft, scroll);
        }
        if (offset > 0) scroll.post(() -> scroll.scrollTo(0, offset));
    }

    private void bindEditorToolbar(Draft draft) {
        LinearLayout header = activity.row();
        Button back = textControl("清單", "返回拼報清單", this::goBack);
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
        Button options = textControl("設定", "拼報設定", () -> editorOptions(draft));
        header.addView(options);
        editorBar.addView(header);

        LinearLayout actions = activity.row();
        if (selectedIds.isEmpty()) {
            addToolbarAction(actions, "插入", "插入剪輯", 1, () -> pickClip(draft));
            addToolbarAction(actions, "新增", "新增區塊", 1, () -> chooseBlockKind(draft));
            String label = reading ? "詳細" : "精簡";
            Button mode = addToolbarAction(actions, label, "切換為" + label + "模式", 1,
                    () -> toggleReading(draft));
            mode.setSelected(reading);
            if (reading) mode.setTextColor(accent);
            addToolbarAction(actions, "預覽", "預覽全文", 1, () -> preview(draft));
            addToolbarAction(actions, "複製", "複製全文", 1, () -> copy(draft));
            addToolbarAction(actions, "匯出", "匯出 ZIP", 1, () -> export(draft));
        } else {
            TextView count = activity.text("已選 " + selectedIds.size() + " 張", 14, ink, true);
            count.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            actions.addView(count, new LinearLayout.LayoutParams(0, -2, 1.2f));
            addToolbarAction(actions, "取消", "退出多選", 1, () -> {
                selectedIds.clear();
                renderDraft(draft);
            });
            Button group = addToolbarAction(actions, "串聯", "串聯選取卡片", 1, () -> createGroup(draft));
            group.setEnabled(draft.canGroup(selectedIds));
            if (!group.isEnabled()) group.setAlpha(0.5f);
            Button delete = addToolbarAction(actions, "刪除", "刪除選取卡片", 1, () -> deleteSelected(draft));
            delete.setTextColor(danger);
        }
        editorBar.addView(actions);
    }

    private Button textControl(String label, String description, Runnable action) {
        Button button = activity.button(label, Color.TRANSPARENT, ink, () -> {
            if (activity.canAct()) action.run();
        });
        button.setTextSize(13);
        button.setPadding(activity.dp(3), activity.dp(4), activity.dp(3), activity.dp(4));
        button.setMinWidth(activity.dp(48));
        button.setMinimumWidth(activity.dp(48));
        button.setContentDescription(description);
        return button;
    }

    private Button addToolbarAction(LinearLayout row, String label, String description, float weight,
                                    Runnable action) {
        Button control = textControl(label, description, action);
        row.addView(control, new LinearLayout.LayoutParams(0, -2, weight));
        return control;
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
        reading = !reading;
        renderDraft(draft);
    }

    private void resetEditorMode() {
        reading = false;
        cardImages = Collections.emptyMap();
        sourceDifferences = Collections.emptySet();
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
        container.setBackground(GroupColors.frame(activity, group.color()));
        container.setPadding(activity.dp(8), activity.dp(6), activity.dp(8), activity.dp(8));

        boolean editing = !reading && selectedIds.isEmpty();
        Runnable toggleFold = () -> saveDraft(draft, draft.toggleGroup(group));
        if (!reading) {
            LinearLayout header = activity.row();
            Button title = dragTitle(group.title(), "排序串聯 " + group.title() + "，拖曳可移動整組", order);
            title.setEnabled(editing);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            if (editing) {
                outer.addCard(container, title);
                header.addView(foldButton(!group.collapsed(), "串聯 " + group.title(), toggleFold));
                header.addView(textControl("選項", "串聯選項 " + group.title(), () -> groupOptions(draft, group)));
            }
            container.addView(header);
        } else if (group.collapsed()) {
            container.addView(textControl("▸ 展開串聯", "展開串聯 " + group.title(), toggleFold));
        }
        if (group.collapsed()) return container;

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
        activity.runForeground(() -> editorData(vault.updateDraft(original, changed), null), stored -> {
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
                        GroupColors.show(activity, group, draft.sensitive(),
                                color -> saveDraft(draft, draft.updateGroup(group, group.title(), color)));
                    } else {
                        saveDraft(draft, draft.ungroup(group));
                    }
                }).setNegativeButton("取消", null));
    }

    private LinearLayout blockCard(Draft draft, int index, DraftReorder reorder, Runnable order) {
        Draft.Block block = draft.blocks().get(index);
        LinearLayout item = panel();
        boolean selecting = !selectedIds.isEmpty();
        boolean editing = !reading && !selecting;
        boolean selected = selectedIds.contains(block.id());
        item.setSelected(selected);
        Runnable toggleFold = () -> saveDraft(draft, draft.toggleBlock(index));

        Draft.Block.Kind kind = block.kind();
        Button fold = null;
        if (!reading) {
            String type = block.image() ? "圖片" : kind == Draft.Block.Kind.TEXT
                    && Vault.isWebLink(block.content()) ? "網址" : kind.label;
            LinearLayout header = activity.row();
            Button handle = dragTitle(type, "排序卡片，" + type + "，可拖曳或點按排序", order);
            handle.setEnabled(editing);
            header.addView(handle, new LinearLayout.LayoutParams(0, -2, 1));
            if (editing) {
                reorder.addCard(item, handle);
                if (kind != Draft.Block.Kind.DIVIDER) {
                    fold = foldButton(block.expanded(), "內文", toggleFold);
                    header.addView(fold);
                }
                header.addView(textControl("選項", "卡片選項", () -> cardOptions(draft, index)));
            } else {
                header.addView(activity.text(selected ? "✓ 已選取" : "未選取", 13, accent, true));
            }
            item.addView(header);
        }

        if (sourceDifferences.contains(block.id())) {
            TextView hint = activity.text("與來源不同", 12, accent, true);
            Drawable icon = activity.getDrawable(R.drawable.ic_source_difference).mutate();
            int size = activity.dp(16);
            icon.setBounds(0, 0, size, size);
            icon.setTint(accent);
            hint.setCompoundDrawablesRelative(icon, null, null, null);
            hint.setCompoundDrawablePadding(activity.dp(4));
            item.addView(hint, activity.margins(-1, -2, 0, 5));
        }

        LinearLayout body = activity.column();
        body.setMinimumHeight(activity.dp(24));
        if (!reading) {
            body.setOnLongClickListener(view -> {
                toggleSelected(draft, block.id());
                return true;
            });
            if (selecting) body.setOnClickListener(view -> toggleSelected(draft, block.id()));
        }

        View content = kind == Draft.Block.Kind.CHECKLIST ? checklistBlock(draft, index, editing)
                : previewBlock(block, cardImages);
        if (!block.expanded()) {
            if (content instanceof TextView text) {
                text.setMaxLines(3);
                text.setEllipsize(TextUtils.TruncateAt.END);
            } else if (content instanceof ImageView image) {
                image.setMaxHeight(activity.dp(120));
            }
        }
        body.addView(content, activity.margins(-1, -2, 0, 0));
        item.addView(body, activity.margins(-1, -2, reading ? 0 : 4, 0));
        if (kind != Draft.Block.Kind.DIVIDER && (reading || editing)) {
            bindFoldAction(body, content, block, fold, toggleFold);
        }
        return item;
    }

    private Button dragTitle(String title, String description, Runnable action) {
        Button control = textControl(title, description, action);
        control.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        control.setTextSize(15);
        control.setMaxLines(2);
        control.setEllipsize(TextUtils.TruncateAt.END);
        control.setAccessibilityHeading(true);
        Drawable grip = activity.getDrawable(R.drawable.ic_reorder).mutate();
        int size = activity.dp(16);
        grip.setBounds(0, 0, size, size);
        grip.setTint(muted);
        control.setCompoundDrawablesRelative(grip, null, null, null);
        control.setCompoundDrawablePadding(activity.dp(6));
        return control;
    }

    private Button foldButton(boolean expanded, String target, Runnable action) {
        Button control = textControl(expanded ? "▾" : "▸",
                (expanded ? "摺疊" : "展開") + target, action);
        control.setTextSize(20);
        return control;
    }

    private void cardOptions(Draft draft, int index) {
        Draft.Block block = draft.blocks().get(index);
        if (!block.image() && !block.sourceId().isEmpty()) {
            activity.runForeground(() -> vault.draftSource(draft, index, false),
                    source -> showCardOptions(draft, index, source));
        } else {
            showCardOptions(draft, index, null);
        }
    }

    private void showCardOptions(Draft draft, int index, Vault.SourceChange source) {
        Draft.Block block = draft.blocks().get(index);
        boolean editable = !block.image() && block.kind() != Draft.Block.Kind.DIVIDER;
        ArrayList<String> labels = new ArrayList<>();
        if (editable) labels.add("編輯");
        if (source != null) labels.add("從剪輯更新");
        labels.add("刪除");
        activity.show(new AlertDialog.Builder(activity).setTitle("卡片選項")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    switch (labels.get(which)) {
                        case "編輯" -> editBlock(draft, index);
                        case "從剪輯更新" -> compareSource(draft, index, source.requiresVerification());
                        default -> removeBlock(draft, index);
                    }
                }).setNegativeButton("取消", null));
    }

    private void compareSource(Draft draft, int index, boolean secure) {
        activity.authorize(secure, "比較來源剪輯",
                () -> activity.runForeground(() -> vault.draftSource(draft, index, secure), source -> {
                    if (source == null || source.content() == null) {
                        activity.toast("來源剪輯目前無法更新。");
                        return;
                    }
                    LinearLayout body = activity.dialogBody();
                    body.addView(sourceExcerpt("目前拼報", draft.blocks().get(index).content()));
                    body.addView(sourceExcerpt("來源剪輯", source.content()), activity.margins(-1, -2, 8, 0));
                    ScrollView scroll = new ScrollView(activity);
                    scroll.addView(body);
                    activity.show(new AlertDialog.Builder(activity).setTitle("從剪輯更新").setView(scroll)
                            .setNegativeButton("取消", null)
                            .setPositiveButton("更新卡片", (dialog, which) -> activity.runForeground(
                                    () -> editorData(vault.refreshDraftSource(draft, index, source, secure), null),
                                    this::renderDraft)), secure);
                }));
    }

    private View sourceExcerpt(String label, String value) {
        LinearLayout card = panel();
        card.addView(activity.text(label, 13, accent, true));
        card.addView(activity.text(value, 16, ink, false), activity.margins(-1, -2, 6, 0));
        return card;
    }

    private void bindFoldAction(LinearLayout body, View content, Draft.Block block, Button fold, Runnable action) {
        if (fold != null) fold.setVisibility(View.INVISIBLE);
        String label = block.expanded() ? "摺疊內文" : "展開內文";
        Runnable refresh = () -> {
            boolean foldable = block.expanded() || content instanceof ImageView
                    || (block.kind() == Draft.Block.Kind.CHECKLIST && Checklist.items(block.content()).size() > 3)
                    || exceedsLineLimit(content);
            if (fold != null) {
                fold.setVisibility(foldable ? View.VISIBLE : View.INVISIBLE);
            } else {
                body.setOnClickListener(foldable ? view -> {
                    if (activity.canAct()) action.run();
                } : null);
                body.setClickable(foldable);
                body.setFocusable(foldable);
                body.setAccessibilityDelegate(foldable ? new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK, label));
                    }
                } : null);
            }
        };
        int[] measuredWidth = {-1};
        content.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            if (measuredWidth[0] != width) {
                measuredWidth[0] = width;
                refresh.run();
            }
        });
    }

    private boolean exceedsLineLimit(View view) {
        if (view instanceof TextView text) {
            int max = text.getMaxLines();
            int width = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
            if (max < 1 || max > 3 || width <= 0) return false;
            CharSequence content = text.getText();
            return StaticLayout.Builder.obtain(content, 0, content.length(), text.getPaint(), width)
                    .setBreakStrategy(text.getBreakStrategy())
                    .setHyphenationFrequency(text.getHyphenationFrequency())
                    .setMaxLines(max + 1).build().getLineCount() > max;
        }
        if (view instanceof LinearLayout list) {
            for (int index = 0; index < list.getChildCount(); index++) {
                if (exceedsLineLimit(list.getChildAt(index))) return true;
            }
        }
        return false;
    }

    private void chooseBlockKind(Draft draft) {
        Draft.Block.Kind[] kinds = Draft.Block.Kind.values();
        DraftSheet sheet = new DraftSheet(activity, "新增區塊", null, draft.sensitive());
        ListView list = pickerCards(kinds.length, index -> kinds[index].label, index -> null, index -> false);
        sheet.body.addView(list, new LinearLayout.LayoutParams(-1, -1));
        list.setOnItemClickListener((parent, row, index, id) -> {
            sheet.dismiss();
            addBlock(draft, kinds[index]);
        });
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

    private View checklistBlock(Draft draft, int index, boolean editing) {
        LinearLayout list = activity.column();
        List<Checklist.Item> items = Checklist.items(draft.blocks().get(index).content());
        boolean expanded = draft.blocks().get(index).expanded();
        int visibleCount = expanded ? items.size() : Math.min(3, items.size());
        for (int position = 0; position < visibleCount; position++) {
            int itemIndex = position;
            Checklist.Item item = items.get(position);
            CheckBox box = activity.checkbox(item.text(), item.checked());
            box.setTextColor(ink);
            box.setTextSize(16);
            if (!expanded) {
                box.setMaxLines(1);
                box.setEllipsize(TextUtils.TruncateAt.END);
            }
            if (!editing) {
                box.setClickable(false);
                box.setFocusable(false);
                list.addView(box, new LinearLayout.LayoutParams(-1, -2));
                continue;
            }
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
        activity.runForeground(() -> editorData(vault.updateDraft(draft, draft.withContent(draft.title(), blocks)), null),
                this::renderDraft, failure);
    }

    private View textBlock(Draft.Block block) {
        Draft.Block.Kind kind = block.kind();
        if (kind == Draft.Block.Kind.DIVIDER) {
            LinearLayout divider = activity.column();
            View line = new View(activity);
            line.setBackgroundColor(muted);
            line.setContentDescription("分隔線");
            divider.addView(line, activity.margins(-1, activity.dp(1), 6, 6));
            return divider;
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
        activity.runForeground(() -> editorData(vault.updateDraft(original, changed), null), this::renderDraft);
    }

    private Map<String, Bitmap> loadImages(Draft draft, Map<String, Bitmap> cached, boolean allowMissing)
            throws Exception {
        Map<String, Bitmap> images = new HashMap<>();
        for (Draft.Block block : draft.blocks()) {
            if (!block.image() || images.containsKey(block.sourceId())) continue;
            if (cached.containsKey(block.sourceId())) {
                images.put(block.sourceId(), cached.get(block.sourceId()));
                continue;
            }
            byte[] bytes;
            try {
                bytes = vault.draftImage(draft, block);
            } catch (IOException | GeneralSecurityException error) {
                if (!allowMissing) throw error;
                continue;
            }
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
        activity.runForeground(() -> loadImages(draft, Collections.emptyMap(), false), images -> {
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
        if (entry.sensitive()) return "私密 · " + entry.privateTitle();
        return entry.content();
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

    private LinearLayout panel() {
        LinearLayout panel = activity.column();
        panel.setBackground(activity.panel(17));
        panel.setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12));
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
