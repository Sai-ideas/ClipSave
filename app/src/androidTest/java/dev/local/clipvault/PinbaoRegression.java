package dev.local.clipvault;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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
            reorder(vault, UUID.randomUUID().toString());
            workspace(vault, UUID.randomUUID().toString());
            var config = getTargetContext().getResources().getConfiguration();
            int dpi = getTargetContext().getResources().getDisplayMetrics().densityDpi;
            report.putString("stream", "PASS checklist, privacy, sticky toolbar, selection and grouped drag at " + config.screenWidthDp
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

    private void workspace(Vault vault, String marker) throws Exception {
        String name = "串聯驗收 " + marker.substring(0, 8);
        Draft draft = vault.createDraft(name);
        MainActivity ui = null;
        try {
            ArrayList<Draft.Block> blocks = new ArrayList<>();
            for (int index = 0; index < 10; index++) {
                blocks.add(Draft.Block.text(Draft.Block.PARAGRAPH, "排序項目 " + index));
            }
            vault.updateDraft(draft, draft.withContent(name, blocks));
            ui = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            MainActivity activity = ui;
            await(() -> onMain(() -> activity.canAct() && button(root(activity), "拼報") != null), "home");
            onMain(() -> button(root(activity), "拼報").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), name) != null), "workspace shelf");
            onMain(() -> button((View) named(root(activity), name).getParent(), "開啟").performClick());
            await(() -> onMain(() -> activity.canAct() && named(root(activity), "排序項目 2") != null), "workspace editor");
            ScrollView content = onMain(() -> (ScrollView) find(root(activity), v -> v instanceof ScrollView));
            Point export = onMain(() -> center(action(activity, "匯出 ZIP")));
            runOnMainSync(() -> content.scrollTo(0, 200));
            check(onMain(() -> content.getScrollY() > 0 && export.equals(center(action(activity, "匯出 ZIP")))),
                    "export stays in sticky toolbar");
            runOnMainSync(() -> content.scrollTo(0, 0));

            onMain(() -> action(activity, "純內文").performClick());
            await(() -> onMain(() -> action(activity, "回到編輯") != null && activity.canAct()), "reading mode");
            check(onMain(() -> named(root(activity), "排序項目 2") != null
                    && button(root(activity), "排序") == null && action(activity, "預覽全文") != null),
                    "reading mode retains content and output actions");
            onMain(() -> action(activity, "回到編輯").performClick());
            await(() -> onMain(() -> button(root(activity), "排序") != null), "editing restored");

            onMain(() -> card(activity, 0).performLongClick());
            onMain(() -> card(activity, 2).performClick());
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
            Point heading = onMain(() -> center(action(activity, "排序串聯 驗收串聯，拖曳可移動整組")));
            Point after = onMain(() -> lowerPart(card(activity, 1), heading.x()));
            long drag = startDrag(heading, after);
            dragEvent(drag, MotionEvent.ACTION_UP, after);
            await(() -> storedOrder(vault, draft.id(), blocks.get(1), blocks.get(0)) && onMain(activity::canAct),
                    "move whole group past outside card");

            Point inner = onMain(() -> center(button(card(activity, 2), "排序")));
            Point before = onMain(() -> {
                View card = card(activity, 0);
                return new Point(inner.x(), center(card).y() - card.getHeight() / 4f);
            });
            drag = startDrag(inner, before);
            dragEvent(drag, MotionEvent.ACTION_UP, before);
            await(() -> storedOrder(vault, draft.id(), blocks.get(1), blocks.get(2)) && onMain(activity::canAct),
                    "member moves inside group");

            Point inside = onMain(() -> center(button(card(activity, 0), "排序")));
            Point outside = onMain(() -> lowerPart(card(activity, 1), inside.x()));
            drag = startDrag(inside, outside);
            dragEvent(drag, MotionEvent.ACTION_UP, outside);
            await(() -> onMain(() -> card(activity, 0).getAlpha() == 1f), "outside group drop finishes");
            check(storedOrder(vault, draft.id(), blocks.get(1), blocks.get(2)), "member cannot leave group");

            onMain(() -> card(activity, 0).performLongClick());
            onMain(() -> card(activity, 1).performClick());
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
            Point handle = onMain(() -> center(button(card(activity, 0), "排序")));
            Point afterSecond = onMain(() -> {
                View second = card(activity, 1);
                Point center = center(second);
                return new Point(handle.x(), center.y() + second.getHeight() / 4f);
            });
            long drag = startDrag(handle, afterSecond);
            dragEvent(drag, MotionEvent.ACTION_UP, afterSecond);
            await(() -> storedOrder(vault, draft.id(), blocks.get(1), blocks.get(0)) && onMain(activity::canAct),
                    "insert after second card");

            Point cancelHandle = onMain(() -> center(button(card(activity, 1), "排序")));
            Point outside = onMain(() -> new Point(cancelHandle.x(), center(named(root(activity), title)).y()));
            drag = startDrag(cancelHandle, outside);
            dragEvent(drag, MotionEvent.ACTION_UP, outside);
            await(() -> onMain(() -> card(activity, 1).getAlpha() == 1f), "outside drop restores opacity");
            check(storedOrder(vault, draft.id(), blocks.get(1), blocks.get(0)), "outside drop retains order");

            Point edgeHandle = onMain(() -> center(button(card(activity, 1), "排序")));
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
        return (View) named(root(activity), "排序項目 " + index).getParent();
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
