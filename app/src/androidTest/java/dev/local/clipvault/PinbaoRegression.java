package dev.local.clipvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Run on an unlocked test device with 剪存's optional app lock disabled. */
public final class PinbaoRegression extends Instrumentation {
    private record Point(float x, float y) {}

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle report = new Bundle();
        try {
            checklist();
            Vault vault = Vault.get(getTargetContext());
            arrangement();
            privacy(vault, UUID.randomUUID().toString());
            sourceUpdates(vault, UUID.randomUUID().toString());
            reorder(vault, UUID.randomUUID().toString());
            workspace(vault, UUID.randomUUID().toString());
            var config = getTargetContext().getResources().getConfiguration();
            int dpi = getTargetContext().getResources().getDisplayMetrics().densityDpi;
            report.putString("stream", "PASS Pinbao source update, sheets, stable surface, and long-press drag at " + config.screenWidthDp
                    + "dp, font scale " + config.fontScale + ", density " + dpi + "dpi\n");
            finish(Activity.RESULT_OK, report);
        } catch (Throwable error) {
            report.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, report);
        }
    }

    private static void checklist() {
        String input = "  \\[x]  \r\n  \\- [ ] 原文 * _ ` 🧩  \r\n\r\n- [X] 完成\n[x]ylophone\n\t";
        List<Checklist.Item> expected = List.of(new Checklist.Item("[x]", false),
                new Checklist.Item("- [ ] 原文 * _ ` 🧩", false), new Checklist.Item("完成", true),
                new Checklist.Item("[x]ylophone", false));
        check(Checklist.items(input).equals(expected), "literal markers, Unicode, CRLF and blank lines");
        String normalized = Checklist.normalize(input);
        check(Checklist.items(normalized).equals(expected), "checked and literal text round-trip");
        check(normalized.equals(Checklist.normalize(normalized)), "saved representation is stable");
        check(Checklist.render(expected, false).contains("☑ 完成"), "plain text state");
    }

    private static void privacy(Vault vault, String marker) throws Exception {
        List<String> clips = new ArrayList<>();
        Draft draft = vault.createDraft("Privacy " + marker);
        try {
            Vault.Entry text = vault.addText(marker, false, "");
            clips.add(text.id());
            Vault.Entry image = vault.addAttachment("image/png", marker + ".png",
                    marker.getBytes(StandardCharsets.UTF_8), false, "");
            clips.add(image.id());
            Vault.Entry link = vault.addText("https://example.com/" + marker, true, "連結提示");
            clips.add(link.id());
            check(link.privateTitle().equals("連結提示"), "description set on new capture");

            check(!vault.addDraftClips(draft.id(), List.of(text.id(), image.id()), false).sensitive(), "public copies");
            vault.protectEntry(text.id(), "  工作帳號\n副本  ");
            vault.protectEntry(image.id(), "");
            check(vault.entry(text.id()).privateTitle().equals("工作帳號 副本"), "conversion description");
            check(vault.entry(image.id()).privateTitle().equals("****"), "blank description falls back to mask");
            check(vault.addText(marker, true, "new capture").privateTitle().equals("工作帳號 副本"),
                    "duplicate capture preserves the existing description");
            vault.updateText(text.id(), marker + " edited");
            check(vault.entry(text.id()).privateTitle().equals("工作帳號 副本"), "content edits preserve description");
            vault.updateOptions(text.id(), true, true, "fixture", "已編輯提示");
            Vault.Entry renamed = vault.entry(text.id());
            check(Vault.Entry.fromJson(renamed.toJson()).privateTitle().equals("已編輯提示"),
                    "edited description survives serialization");
            JSONObject legacyEntry = renamed.toJson();
            legacyEntry.remove("privateDescription");
            check(Vault.Entry.fromJson(legacyEntry).privateTitle().equals("****"), "legacy entry fallback");
            vault.updateOptions(text.id(), true, true, "fixture", "　 ");
            check(vault.entry(text.id()).privateTitle().equals("****"), "clear description");
            check(vault.entry(text.id()).sensitive() && vault.entry(text.id()).starred(), "options keep privacy");
            try {
                vault.addDraftClips(draft.id(), List.of(link.id()), false);
                throw new AssertionError("private insert accepted without verification");
            } catch (Vault.Limit expected) {}
            Draft stored = vault.addDraftClips(draft.id(), List.of(link.id()), true);
            check(stored.blocks().stream().allMatch(Draft.Block::sensitive), "all three copies protected");
            vault.delete(text.id());
            vault.delete(image.id());
            Draft.Block imageCopy = stored.blocks().get(1);
            check(Arrays.equals(vault.draftImage(stored, imageCopy), marker.getBytes(StandardCharsets.UTF_8)),
                    "image copy retained after source deletion");
            Draft.Block editedText = stored.blocks().get(0).withContent("Edited fixture");
            stored = vault.updateDraft(stored, stored.withContent(stored.title(), List.of(editedText, imageCopy)));
            check(editedText.sensitive() && Draft.fromJson(stored.toJson()).sensitive(), "editing and serialization");
            stored = vault.updateDraft(stored, stored.withContent(stored.title(), List.of(imageCopy)));
            check(stored.sensitive(), "another protected copy remains");
            Draft plain = vault.updateDraft(stored, stored.withContent(stored.title(),
                    List.of(Draft.Block.text(Draft.Block.PARAGRAPH, "Public paragraph"))));
            check(!plain.sensitive(), "last private copy removed");
            JSONObject legacy = stored.toJson();
            JSONArray blocks = legacy.getJSONArray("blocks");
            blocks.getJSONObject(0).remove("sensitive");
            check(Draft.fromJson(legacy).blocks().get(0).sensitive(), "legacy copy with unknown source stays private");
        } finally {
            vault.deleteDraft(vault.draft(draft.id()));
            for (String id : clips) vault.delete(id);
        }
    }

    private static void arrangement() throws Exception {
        Draft first = Draft.create("Arrangement");
        Draft.Block a = Draft.Block.text(Draft.Block.PARAGRAPH, "A");
        Draft.Block b = Draft.Block.text(Draft.Block.PARAGRAPH, "B");
        Draft.Block c = Draft.Block.text(Draft.Block.PARAGRAPH, "C");
        Draft.Block d = Draft.Block.text(Draft.Block.PARAGRAPH, "D");
        Draft source = first.withContent(first.title(), List.of(a, b, c, d));
        Draft grouped = source.group(Set.of(c.id(), a.id()), "Hidden heading", 1);
        check(grouped.blocks().equals(List.of(a, c, b, d)), "group keeps original relative order");
        check(!grouped.canGroup(Set.of(a.id(), b.id())), "cannot nest grouped cards");
        check(Draft.fromJson(grouped.toJson()).equals(grouped), "group and color round-trip");
        Draft folded = grouped.toggleBlock(0).toggleGroup(grouped.groups().get(0));
        Draft roundTrip = Draft.fromJson(folded.toJson());
        check(roundTrip.blocks().get(0).expanded() && roundTrip.groups().get(0).collapsed(),
                "card and group state restored");
        check(roundTrip.updatedAt() == grouped.updatedAt(), "folding does not change content timestamp");
        check(Article.render(grouped, true).equals(Article.render(source.withContent(source.title(),
                List.of(a, c, b, d)), true)), "output ignores group metadata");
        Draft moved = grouped.moveSection(0, 2);
        check(moved.blocks().equals(List.of(b, d, a, c)), "entire group moves");
        Draft reordered = moved.moveInside(moved.sections().get(2), 1, 0);
        check(reordered.blocks().equals(List.of(b, d, c, a)), "group members reorder in place");
        Draft dissolved = reordered.ungroup(reordered.groups().get(0));
        check(dissolved.groups().isEmpty() && dissolved.blocks().equals(reordered.blocks()), "ungroup retains order");
        check(grouped.withContent(grouped.title(), List.of(b, d)).groups().isEmpty(), "empty group removed");

        Draft manual = source.withManualLock(true);
        check(manual.sensitive() && !manual.containsPrivateBlocks(), "manual lock alone");
        check(Draft.fromJson(manual.toJson()).equals(manual), "manual lock round-trip");
        Draft privateOnly = manual.withContent(manual.title(), List.of(a.protect(), b)).withManualLock(false);
        check(privateOnly.sensitive() && !privateOnly.manualLock(), "private block overrides manual toggle");
        check(!privateOnly.withContent(privateOnly.title(), List.of(b)).sensitive(), "remove final private block");
        check(manual.withContent(manual.title(), List.of(b)).sensitive(), "manual lock survives block removal");
        JSONObject legacy = source.toJson();
        legacy.remove("manualLock");
        legacy.remove("groups");
        check(Draft.fromJson(legacy).equals(source), "legacy ungrouped draft opens unchanged");
    }

    private static void sourceUpdates(Vault vault, String marker) throws Exception {
        Vault.Entry entry = vault.addText(marker, false, "");
        Draft draft = vault.createDraft("來源 " + marker);
        try {
            Draft current = vault.addDraftClips(draft.id(), List.of(entry.id()), false);
            check(vault.draftSource(current, 0, false) == null, "equal source has no update");
            check(vault.draftSourceDifferences(current).isEmpty(), "equal source has no indicator");
            vault.updateText(entry.id(), marker + " updated");
            Vault.SourceChange change = vault.draftSource(current, 0, false);
            check(change.content().endsWith(" updated"), "source difference");
            Draft.Block original = current.blocks().get(0);
            check(vault.draftSourceDifferences(current).equals(Set.of(original.id())), "changed source marks its card");
            current = vault.refreshDraftSource(current, 0, change, false);
            check(current.blocks().get(0).id().equals(original.id())
                    && current.blocks().get(0).content().equals(change.content()), "update keeps block identity");
            check(vault.draftSourceDifferences(current).isEmpty(), "update clears indicator");
            Draft locallyEdited = vault.updateDraft(current, current.withContent(current.title(),
                    List.of(current.blocks().get(0).withContent(marker + " local"))));
            check(vault.draftSourceDifferences(locallyEdited).equals(Set.of(original.id())), "local edit marks its card");
            current = vault.refreshDraftSource(locallyEdited, 0, vault.draftSource(locallyEdited, 0, false), false);
            vault.updateText(entry.id(), marker + " again");
            try {
                vault.refreshDraftSource(current, 0, change, false);
                throw new AssertionError("stale source accepted");
            } catch (Vault.Limit expected) {}
            vault.protectEntry(entry.id(), "");
            current = vault.draft(draft.id());
            check(vault.draftSourceDifferences(current).equals(Set.of(original.id())), "private status contains only card ID");
            check(vault.draftSource(current, 0, false).content() == null, "private preview gated");
            try {
                vault.refreshDraftSource(current, 0, vault.draftSource(current, 0, false), false);
                throw new AssertionError("private update accepted without verification");
            } catch (Vault.Limit expected) {}
            check(vault.draftSource(current, 0, true).content().endsWith(" again"), "authorized source preview");
            vault.delete(entry.id());
            check(vault.draftSource(current, 0, true) == null
                    && vault.draftSourceDifferences(current).isEmpty()
                    && vault.draft(draft.id()).blocks().get(0).content().equals(change.content()), "missing source keeps copy");
        } finally {
            vault.deleteDraft(vault.draft(draft.id()));
            vault.delete(entry.id());
        }
    }

    private void workspace(Vault vault, String marker) throws Exception {
        String name = "串聯驗收 " + marker.substring(0, 8);
        String longText = "第一行\n第二行\n第三行\n第四行\n第五行";
        String wrappedText = String.join(" ", java.util.Collections.nCopies(80, "自動換行"));
        Draft draft = vault.createDraft(name);
        Vault.Entry source = vault.addText("插入驗收 " + marker, false, "");
        MainActivity ui = null;
        try {
            vault.updateOptions(source.id(), true, false, "", null);
            ArrayList<Draft.Block> blocks = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                blocks.add(Draft.Block.text(Draft.Block.PARAGRAPH, "排序項目 " + index));
            }
            blocks.add(Draft.Block.text(Draft.Block.PARAGRAPH, longText));
            blocks.add(Draft.Block.text(Draft.Block.PARAGRAPH, wrappedText));
            blocks.add(Draft.Block.text(Draft.Block.Kind.DIVIDER.mime, ""));
            vault.updateDraft(draft, draft.withContent(name, blocks));
            ui = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity activity = ui;
            await(() -> onMain(() -> activity.canAct() && button(root(activity), "拼報") != null), "home");
            onMain(() -> button(root(activity), "拼報").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), name) != null), "workspace shelf");
            onMain(() -> button((View) named(root(activity), name).getParent(), "開啟").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), "排序項目 2") != null), "workspace editor");
            onMain(() -> action(activity, "新增區塊").performClick());
            runOnMainSync(() -> {
                AlertDialog shown = dialog(activity);
                ListView choices = (ListView) find(shown.getWindow().getDecorView(), v -> v instanceof ListView);
                check(choices != null && choices.getCount() > 3, "card block picker");
                shown.dismiss();
            });
            onMain(() -> action(activity, "插入剪輯").performClick());
            await(() -> onMain(() -> {
                AlertDialog shown = dialog(activity);
                if (shown == null) return false;
                ListView list = (ListView) find(shown.getWindow().getDecorView(), v -> v instanceof ListView);
                return list != null && list.getChildCount() > 0;
            }), "card clip picker");
            runOnMainSync(() -> {
                View dialogRoot = dialog(activity).getWindow().getDecorView();
                ListView choices = (ListView) find(dialogRoot, v -> v instanceof ListView);
                View first = choices.getChildAt(0);
                check(first != null && named(first, source.content()) != null, "preview on clip card");
                choices.performItemClick(first, 0, 0);
                button(dialogRoot, "插入 1 筆").performClick();
            });
            await(() -> onMain(() -> activity.canAct() && named(root(activity), source.content()) != null), "insert card");
            check(onMain(() -> named(root(activity), "與來源不同") == null), "new copy is unmarked");
            onMain(() -> action(activity, "剪輯").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), source.content()) != null
                    && button(root(activity), "新增文字") != null), "clip list ready");
            onMain(() -> button((View) named(root(activity), source.content()).getParent(), "編輯").performClick());
            runOnMainSync(() -> {
                AlertDialog shown = dialog(activity);
                EditText input = (EditText) find(shown.getWindow().getDecorView(), v -> v instanceof EditText);
                input.setText(source.content() + " 已更新");
                shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            await(() -> onMain(() -> activity.canAct()
                    && named(root(activity), source.content() + " 已更新") != null), "source edited in clip list");
            onMain(() -> action(activity, "拼報").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), "與來源不同") != null),
                    "switching back shows source difference");
            check(onMain(() -> {
                View changed = (View) body(activity, source.content()).getParent();
                TextView hint = (TextView) named(changed, "與來源不同");
                return hint != null && hint.getCompoundDrawablesRelative()[0] != null
                        && named(card(activity, 0), "與來源不同") == null;
            }), "only the changed card has text and icon");
            onMain(() -> action(activity, "切換為精簡模式").performClick());
            check(onMain(() -> named(root(activity), "與來源不同") != null), "compact mode keeps indicator");
            onMain(() -> action(activity, "切換為詳細模式").performClick());
            onMain(() -> cardAction(activity, source.content(), "卡片選項").performClick());
            await(() -> onMain(() -> dialog(activity) != null), "source menu ready");
            runOnMainSync(() -> {
                ListView menu = dialog(activity).getListView();
                check("從剪輯更新".equals(menu.getItemAtPosition(1)), "update option for differences");
                menu.performItemClick(menu.getChildAt(1), 1, 1);
            });
            await(() -> onMain(() -> {
                AlertDialog shown = dialog(activity);
                Button confirm = shown == null ? null : shown.getButton(AlertDialog.BUTTON_POSITIVE);
                return confirm != null && confirm.getText().equals("更新卡片");
            }), "source comparison ready");
            runOnMainSync(() -> {
                AlertDialog shown = dialog(activity);
                View layout = shown.getWindow().getDecorView();
                check(named(layout, source.content()) != null && named(layout, source.content() + " 已更新") != null,
                        "source comparison displays both values");
                shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            await(() -> onMain(() -> activity.canAct()
                    && named(root(activity), source.content() + " 已更新") != null), "source update rendered");
            check(onMain(() -> named(root(activity), "與來源不同") == null), "visual indicator clears after update");
            View editorRoot = onMain(() -> (View) root(activity).findViewById(android.R.id.content));
            ScrollView content = onMain(() -> (ScrollView) find(root(activity), v -> v instanceof ScrollView));
            Point export = onMain(() -> center(action(activity, "匯出 ZIP")));
            runOnMainSync(() -> content.scrollTo(0, 200));
            check(onMain(() -> content.getScrollY() > 0 && export.equals(center(action(activity, "匯出 ZIP")))),
                    "export stays in sticky toolbar");
            runOnMainSync(() -> content.scrollTo(0, 0));

            await(() -> onMain(() -> cardAction(activity, longText, "展開內文").isShown()
                    && cardAction(activity, wrappedText, "展開內文").isShown()), "triangle for hard and soft wrapping");
            check(onMain(() -> ((Button) cardAction(activity, longText, "展開內文")).getText().equals("▸")),
                    "collapsed triangle points right");
            onMain(() -> cardAction(activity, longText, "展開內文").performClick());
            await(() -> onMain(() -> activity.canAct() && ((Button) cardAction(activity, longText,
                    "摺疊內文")).getText().equals("▾")), "expanded triangle points down");
            onMain(() -> cardAction(activity, longText, "摺疊內文").performClick());
            await(() -> onMain(() -> activity.canAct() && cardAction(activity, longText, "展開內文").isShown()),
                    "collapse with triangle");
            check(onMain(() -> content == find(root(activity), v -> v instanceof ScrollView)
                    && editorRoot == root(activity).findViewById(android.R.id.content)), "fold retains editor surface");

            List<Point> toolbarPositions = onMain(() -> toolbarPositions(activity, "切換為精簡模式"));
            onMain(() -> action(activity, "切換為精簡模式").performClick());
            await(() -> onMain(() -> action(activity, "切換為詳細模式") != null && activity.canAct()), "compact mode");
            check(onMain(() -> named(root(activity), "排序項目 2") != null && card(activity, 2).getBackground() != null
                    && action(activity, "卡片選項") == null && action(activity, "預覽全文") != null),
                    "compact mode retains card surface, hides card headers and preserves output actions");
            await(() -> onMain(() -> toolbarPositions.equals(toolbarPositions(activity, "切換為詳細模式"))),
                    "every main toolbar control keeps its position");
            await(() -> onMain(() -> action(activity, "分隔線").getHeight() > 0), "divider laid out");
            check(onMain(() -> action(activity, "分隔線").getHeight() <= activity.dp(2)), "divider is a thin line");
            await(() -> onMain(() -> body(activity, longText).isClickable()), "compact body offers expand");
            check(onMain(() -> ((TextView) named(root(activity), longText)).getMaxLines() == 3), "three line fold");
            onMain(() -> body(activity, longText).performClick());
            await(() -> onMain(() -> activity.canAct()
                    && ((TextView) named(root(activity), longText)).getMaxLines() > 3), "full card");

            ScrollView readingScroll = onMain(() -> (ScrollView) find(root(activity), v -> v instanceof ScrollView));
            runOnMainSync(() -> readingScroll.scrollTo(0, 150));
            int offset = onMain(readingScroll::getScrollY);
            Point navigation = onMain(() -> center(action(activity, "剪輯")));
            onMain(() -> action(activity, "剪輯").performClick());
            await(() -> onMain(() -> activity.canAct() && button(root(activity), "新增文字") != null), "clips");
            check(onMain(() -> navigation.equals(center(action(activity, "剪輯")))), "fixed bottom location");
            onMain(() -> action(activity, "拼報").performClick());
            await(() -> onMain(() -> action(activity, "切換為詳細模式") != null && activity.canAct()
                    && ((ScrollView) find(root(activity), v -> v instanceof ScrollView)).getScrollY() == offset),
                    "return to draft and restore reading position");
            onMain(() -> action(activity, "切換為詳細模式").performClick());
            await(() -> onMain(() -> dragHandle(activity, 0) != null), "detail restored");
            runOnMainSync(() -> ((ScrollView) find(root(activity), v -> v instanceof ScrollView)).scrollTo(0, 0));

            onMain(() -> cardAction(activity, "排序項目 0", "卡片選項").performClick());
            runOnMainSync(() -> {
                AlertDialog options = dialog(activity);
                check("編輯".equals(options.getListView().getItemAtPosition(0))
                        && "刪除".equals(options.getListView().getItemAtPosition(1)), "card menu");
                options.dismiss();
            });
            ScrollView beforeSelection = onMain(() -> (ScrollView) find(root(activity), v -> v instanceof ScrollView));
            longPress(onMain(() -> center(body(activity, "排序項目 0"))));
            check(onMain(() -> beforeSelection == find(root(activity), v -> v instanceof ScrollView)),
                    "selection retains scroll container");
            onMain(() -> body(activity, "排序項目 2").performClick());
            check(onMain(() -> named(root(activity), "已選 2 張") != null), "selection count");
            onMain(() -> action(activity, "串聯選取卡片").performClick());
            runOnMainSync(() -> {
                AlertDialog dialog = dialog(activity);
                EditText input = (EditText) find(dialog.getWindow().getDecorView(), v -> v instanceof EditText);
                input.setText("驗收串聯");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            });
            await(() -> storedOrder(vault, draft.id(), blocks.get(0), blocks.get(2))
                    && onMain(() -> activity.canAct()
                    && action(activity, "串聯選項 驗收串聯") != null), "group created");
            onMain(() -> action(activity, "串聯選項 驗收串聯").performClick());
            runOnMainSync(() -> {
                ListView settings = dialog(activity).getListView();
                settings.performItemClick(settings.getChildAt(1), 1, 1);
            });
            runOnMainSync(() -> {
                View palette = dialog(activity).getWindow().getDecorView();
                check(button(palette, "自訂顏色") != null, "advanced color sliders expandable");
                button(palette, "玫瑰").performClick();
                check(button(palette, "✓ 玫瑰") != null, "new preset selected");
                button(palette, "套用").performClick();
            });
            await(() -> onMain(activity::canAct) && storedColor(vault, draft.id()) == 4, "preset persisted");
            onMain(() -> action(activity, "串聯選項 驗收串聯").performClick());
            runOnMainSync(() -> {
                ListView settings = dialog(activity).getListView();
                settings.performItemClick(settings.getChildAt(1), 1, 1);
            });
            runOnMainSync(() -> {
                View palette = dialog(activity).getWindow().getDecorView();
                button(palette, "自訂顏色").performClick();
                View hue = find(palette, v -> "色相".equals(v.getContentDescription()));
                Bundle progress = new Bundle();
                progress.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 179);
                check(hue.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo
                        .AccessibilityAction.ACTION_SET_PROGRESS.getId(), progress), "custom hue slider");
                button(palette, "套用").performClick();
            });
            await(() -> onMain(activity::canAct) && storedColor(vault, draft.id()) < 0, "custom color persisted");
            onMain(() -> action(activity, "摺疊串聯 驗收串聯").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), "排序項目 2") == null), "group folded");
            Point heading = onMain(() -> center(action(activity, "排序串聯 驗收串聯，拖曳可移動整組")));
            Point after = onMain(() -> lowerPart(card(activity, 1), heading.x()));
            long drag = startDrag(heading, after);
            dragEvent(drag, MotionEvent.ACTION_UP, after);
            await(() -> storedOrder(vault, draft.id(), blocks.get(1), blocks.get(0)) && onMain(activity::canAct),
                    "move whole group past outside card");
            onMain(() -> action(activity, "切換為精簡模式").performClick());
            await(() -> onMain(() -> action(activity, "展開串聯 驗收串聯") != null
                    && action(activity, "串聯選項 驗收串聯") == null), "compact group open affordance");
            onMain(() -> action(activity, "展開串聯 驗收串聯").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), "排序項目 2") != null
                    && named(root(activity), "驗收串聯") == null), "group unfolds with header hidden");
            onMain(() -> action(activity, "切換為詳細模式").performClick());

            Point inner = onMain(() -> center(dragHandle(activity, 2)));
            Point before = onMain(() -> {
                View card = card(activity, 0);
                return new Point(inner.x(), center(card).y() - card.getHeight() / 4f);
            });
            drag = startDrag(inner, before);
            dragEvent(drag, MotionEvent.ACTION_UP, before);
            await(() -> storedOrder(vault, draft.id(), blocks.get(1), blocks.get(2)) && onMain(activity::canAct),
                    "member moves inside group");

            Point inside = onMain(() -> center(dragHandle(activity, 0)));
            Point outside = onMain(() -> lowerPart(card(activity, 1), inside.x()));
            drag = startDrag(inside, outside);
            dragEvent(drag, MotionEvent.ACTION_UP, outside);
            await(() -> onMain(() -> card(activity, 0).getAlpha() == 1f), "outside group drop finishes");
            check(storedOrder(vault, draft.id(), blocks.get(1), blocks.get(2)), "member cannot leave group");

            onMain(() -> body(activity, "排序項目 0").performLongClick());
            onMain(() -> body(activity, "排序項目 1").performClick());
            onMain(() -> action(activity, "刪除選取卡片").performClick());
            runOnMainSync(() -> dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            await(() -> storedOrder(vault, draft.id(), blocks.get(2), blocks.get(3)) && onMain(activity::canAct),
                    "batch delete removes only selected cards");
        } finally {
            if (ui != null) {
                MainActivity activity = ui;
                runOnMainSync(activity::finish);
            }
            vault.deleteDraft(vault.draft(draft.id()));
            vault.delete(source.id());
        }
    }

    private static int storedColor(Vault vault, String id) {
        try {
            return vault.draft(id).groups().get(0).color();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static AlertDialog dialog(MainActivity activity) {
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("dialog");
            field.setAccessible(true);
            return (AlertDialog) field.get(activity);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Point lowerPart(View card, float x) {
        return new Point(x, center(card).y() + card.getHeight() / 4f);
    }

    private static View cardAction(Activity activity, String content, String description) {
        return find((View) body(activity, content).getParent(),
                view -> description.equals(view.getContentDescription()));
    }

    private static View action(Activity activity, String description) {
        return find(root(activity), view -> view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription()));
    }

    private void reorder(Vault vault, String marker) throws Exception {
        String title = "排序測試 " + marker.substring(0, 8);
        Draft draft = vault.createDraft(title);
        MainActivity ui = null;
        try {
            ArrayList<Draft.Block> blocks = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                blocks.add(Draft.Block.text(Draft.Block.PARAGRAPH, "排序項目 " + index));
            }
            vault.updateDraft(draft, draft.withContent(title, blocks));
            ui = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity activity = ui;
            await(() -> onMain(() -> activity.canAct() && button(root(activity), "拼報") != null), "home");
            onMain(() -> button(root(activity), "拼報").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), title) != null), "shelf");
            onMain(() -> button((View) named(root(activity), title).getParent(), "開啟").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), "排序項目 1") != null), "editor");
            Point quickStart = onMain(() -> center(dragHandle(activity, 1)));
            long swipe = SystemClock.uptimeMillis();
            dragEvent(swipe, MotionEvent.ACTION_DOWN, quickStart);
            Point swipeEnd = new Point(quickStart.x(), quickStart.y() - 100);
            for (int step = 1; step <= 5; step++) {
                dragEvent(swipe, MotionEvent.ACTION_MOVE, new Point(quickStart.x(), quickStart.y() - 20 * step));
                SystemClock.sleep(28);
            }
            SystemClock.sleep(150);
            dragEvent(swipe, MotionEvent.ACTION_UP, swipeEnd);
            ScrollView editorScroll = onMain(() -> (ScrollView) find(root(activity), v -> v instanceof ScrollView));
            check(onMain(editorScroll::getScrollY) > 0, "quick swipe on header scrolls");
            check(storedOrder(vault, draft.id(), blocks.get(0), blocks.get(1)), "quick swipe does not reorder");
            runOnMainSync(() -> editorScroll.scrollTo(0, 0));
            Point press = onMain(() -> center(dragHandle(activity, 0)));
            long hold = SystemClock.uptimeMillis();
            dragEvent(hold, MotionEvent.ACTION_DOWN, press);
            await(() -> onMain(() -> ((Button) dragHandle(activity, 0)).getCurrentTextColor() == Color.WHITE),
                    "white ready cue");
            dragEvent(hold, MotionEvent.ACTION_UP, press);
            await(() -> onMain(() -> ((Button) dragHandle(activity, 0)).getCurrentTextColor() != Color.WHITE),
                    "cue clears when released");
            Point handle = onMain(() -> center(dragHandle(activity, 0)));
            Point afterSecond = onMain(() -> {
                View second = card(activity, 1);
                Point center = center(second);
                return new Point(handle.x(), center.y() + second.getHeight() / 4f);
            });
            long drag = startDrag(handle, afterSecond);
            dragEvent(drag, MotionEvent.ACTION_UP, afterSecond);
            await(() -> storedOrder(vault, draft.id(), blocks.get(1), blocks.get(0)) && onMain(activity::canAct),
                    "insert after second card");

            Point cancelHandle = onMain(() -> center(dragHandle(activity, 1)));
            Point outside = onMain(() -> new Point(cancelHandle.x(), center(named(root(activity), title)).y()));
            drag = startDrag(cancelHandle, outside);
            dragEvent(drag, MotionEvent.ACTION_UP, outside);
            await(() -> onMain(() -> card(activity, 1).getAlpha() == 1f), "outside drop restores opacity");
            check(storedOrder(vault, draft.id(), blocks.get(1), blocks.get(0)), "outside drop retains order");

            Point edgeHandle = onMain(() -> center(dragHandle(activity, 1)));
            ScrollView scroll = onMain(() -> (ScrollView) find(root(activity), v -> v instanceof ScrollView));
            Point edge = onMain(() -> new Point(edgeHandle.x(), center(scroll).y() + scroll.getHeight() / 2f - 10));
            drag = startDrag(edgeHandle, edge);
            await(() -> onMain(scroll::getScrollY) > 150, "edge scrolling");
            Point visibleDestination = onMain(() -> {
                float scrollMiddle = center(scroll).y();
                for (int index = 3; index < blocks.size(); index++) {
                    Point slot = center(card(activity, index));
                    if (Math.abs(slot.y() - scrollMiddle) < scroll.getHeight() / 3f) {
                        return new Point(edgeHandle.x(), slot.y());
                    }
                }
                throw new AssertionError("no later card in view");
            });
            dragEvent(drag, MotionEvent.ACTION_MOVE, visibleDestination);
            dragEvent(drag, MotionEvent.ACTION_UP, visibleDestination);
            await(() -> storedOrder(vault, draft.id(), blocks.get(0), blocks.get(2)) && onMain(activity::canAct),
                    "drop after scrolling");
        } finally {
            if (ui != null) {
                MainActivity activity = ui;
                runOnMainSync(activity::finish);
            }
            vault.deleteDraft(vault.draft(draft.id()));
        }
    }

    private long startDrag(Point start, Point end) {
        long down = SystemClock.uptimeMillis();
        dragEvent(down, MotionEvent.ACTION_DOWN, start);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 140);
        for (int index = 1; index <= 8; index++) {
            float fraction = index / 8f;
            dragEvent(down, MotionEvent.ACTION_MOVE, new Point(start.x() + (end.x() - start.x()) * fraction,
                    start.y() + (end.y() - start.y()) * fraction));
            SystemClock.sleep(26);
        }
        return down;
    }

    private void dragEvent(long started, int action, Point position) {
        MotionEvent event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action,
                position.x(), position.y(), 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            check(getUiAutomation().injectInputEvent(event, true), "inject drag event");
        } finally {
            event.recycle();
        }
    }

    private static boolean storedOrder(Vault vault, String id, Draft.Block first, Draft.Block second) {
        try {
            List<Draft.Block> order = vault.draft(id).blocks();
            return first.equals(order.get(0)) && second.equals(order.get(1));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static View root(Activity activity) {
        return activity.getWindow().getDecorView();
    }

    private static Point center(View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return new Point(location[0] + view.getWidth() / 2f, location[1] + view.getHeight() / 2f);
    }

    private static View card(Activity activity, int index) {
        return (View) body(activity, "排序項目 " + index).getParent();
    }

    private static View body(Activity activity, String content) {
        return (View) named(root(activity), content).getParent();
    }

    private static View dragHandle(Activity activity, int index) {
        return cardAction(activity, "排序項目 " + index, "排序卡片，一般段落，可拖曳或點按排序");
    }

    private static List<Point> toolbarPositions(Activity activity, String mode) {
        return List.of(center(action(activity, "插入剪輯")), center(action(activity, "新增區塊")),
                center(action(activity, mode)), center(action(activity, "預覽全文")),
                center(action(activity, "複製全文")), center(action(activity, "匯出 ZIP")));
    }

    private void longPress(Point point) {
        long down = SystemClock.uptimeMillis();
        dragEvent(down, MotionEvent.ACTION_DOWN, point);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 140);
        dragEvent(down, MotionEvent.ACTION_UP, point);
    }

    private static boolean hasLabel(View view, String label) {
        return view instanceof TextView text && label.contentEquals(text.getText());
    }

    private static View named(View root, String label) {
        return find(root, child -> hasLabel(child, label));
    }

    private static View button(View root, String label) {
        return find(root, child -> child instanceof Button && hasLabel(child, label));
    }

    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = find(group.getChildAt(index), match);
                if (found != null) return found;
            }
        }
        return null;
    }

    private <T> T onMain(Supplier<T> operation) {
        AtomicReference<T> result = new AtomicReference<>();
        runOnMainSync(() -> result.set(operation.get()));
        return result.get();
    }

    private static void await(BooleanSupplier condition, String step) {
        long deadline = SystemClock.uptimeMillis() + 40_000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(150);
        }
        throw new AssertionError("Timed out: " + step);
    }

    private static void check(boolean condition, String step) {
        if (!condition) throw new AssertionError(step);
    }
}
