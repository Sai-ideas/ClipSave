package dev.local.clipvault;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
            privacy(vault, UUID.randomUUID().toString());
            reorder(vault, UUID.randomUUID().toString());
            var config = getTargetContext().getResources().getConfiguration();
            int dpi = getTargetContext().getResources().getDisplayMetrics().densityDpi;
            report.putString("stream", "PASS checklist, privacy and drag at " + config.screenWidthDp
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
