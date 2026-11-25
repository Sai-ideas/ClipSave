package dev.local.clipvault;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.function.IntConsumer;

final class GroupColors {
    // IDs 0–3 are the original adaptive presets. Negative values are opaque custom RGB colors.
    private static final String[] NAMES = {"紫", "藍", "綠", "琥珀", "玫瑰", "紅", "橙", "橄欖", "湖水",
            "靛藍", "棕", "灰"};
    private static final int[] LIGHT = {0, 0, 0, 0, 0xFFA33D74, 0xFFAC4148, 0xFFA85427, 0xFF66751F,
            0xFF20766E, 0xFF5455A1, 0xFF795742, 0xFF646875};
    private static final int[] DARK = {0, 0, 0, 0, 0xFFF0A6CD, 0xFFFFB3B0, 0xFFF6AD82, 0xFFCCD687,
            0xFF85D8CE, 0xFFB7B9FF, 0xFFDCBB9F, 0xFFBBC0D0};

    private final MainActivity activity;
    private final DraftSheet sheet;
    private final GradientDrawable preview;
    private final ArrayList<Button> swatches = new ArrayList<>();
    private final ArrayList<SeekBar> sliders = new ArrayList<>();
    private int selected;

    private GroupColors(MainActivity activity, Draft.Group group, boolean sensitive, IntConsumer apply) {
        this.activity = activity;
        selected = group.color();
        sheet = new DraftSheet(activity, "串聯顏色", "套用", sensitive);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout contents = activity.column();
        scroll.addView(contents);

        TextView sample = activity.text(group.title(), 17, activity.getColor(R.color.vault_text), true);
        sample.setMaxLines(2);
        sample.setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12));
        preview = frame(activity, selected);
        sample.setBackground(preview);
        sheet.body.addView(sample, activity.margins(-1, -2, 0, 8));
        sheet.body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        for (int rowIndex = 0; rowIndex < 3; rowIndex++) {
            LinearLayout row = activity.row();
            contents.addView(row);
            for (int columnIndex = 0; columnIndex < 4; columnIndex++) {
                int index = rowIndex * 4 + columnIndex;
                Button swatch = activity.button(NAMES[index], Color.TRANSPARENT,
                        activity.getColor(R.color.vault_text), () -> choose(index));
                swatch.setGravity(Gravity.CENTER);
                swatch.setPadding(0, activity.dp(4), 0, activity.dp(4));
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
                size.setMargins(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3));
                row.addView(swatch, size);
                swatches.add(swatch);
            }
        }
        LinearLayout custom = activity.column();
        custom.setVisibility(View.GONE);
        Button expand = activity.button("自訂顏色", activity.getColor(R.color.vault_surface_soft),
                activity.getColor(R.color.vault_text), () -> {
                    boolean opening = custom.getVisibility() != View.VISIBLE;
                    custom.setVisibility(opening ? View.VISIBLE : View.GONE);
                    if (opening) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                });
        contents.addView(expand, activity.margins(-1, -2, 8, 0));
        contents.addView(custom);
        addSlider(custom, "色相", 359);
        addSlider(custom, "彩度", 100);
        addSlider(custom, "亮度", 100);
        choose(selected);
        sheet.apply.setOnClickListener(view -> {
            if (!activity.canAct()) return;
            sheet.dismiss();
            apply.accept(selected);
        });
    }

    static void show(MainActivity activity, Draft.Group group, boolean sensitive, IntConsumer apply) {
        new GroupColors(activity, group, sensitive, apply);
    }

    private void addSlider(LinearLayout custom, String name, int max) {
        TextView label = activity.text(name, 14, activity.getColor(R.color.vault_text), true);
        custom.addView(label, activity.margins(-1, -2, 6, 0));
        SeekBar slider = new SeekBar(activity);
        slider.setContentDescription(name);
        slider.setMax(max);
        slider.setMinimumHeight(activity.dp(48));
        custom.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        sliders.add(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser) return;
                int previous = selected;
                selected = Color.HSVToColor(new float[]{sliders.get(0).getProgress(),
                        sliders.get(1).getProgress() / 100f, sliders.get(2).getProgress() / 100f});
                if (previous >= 0) refreshSwatches();
                refreshPreview();
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
    }

    private void choose(int value) {
        selected = value;
        float[] hsv = new float[3];
        Color.colorToHSV(value < 0 ? value : borderColor(activity, value), hsv);
        sliders.get(0).setProgress(Math.round(hsv[0]) % 360);
        sliders.get(1).setProgress(Math.round(hsv[1] * 100));
        sliders.get(2).setProgress(Math.round(hsv[2] * 100));
        refreshSwatches();
        refreshPreview();
    }

    private void refreshSwatches() {
        for (int index = 0; index < swatches.size(); index++) {
            Button button = swatches.get(index);
            boolean active = selected == index;
            button.setText((active ? "✓ " : "") + NAMES[index]);
            button.setContentDescription(NAMES[index] + (active ? "，已選取" : ""));
            button.setSelected(active);
            GradientDrawable shape = frame(activity, index);
            shape.setStroke(activity.dp(active ? 2 : 1), borderColor(activity, index));
            button.setBackground(shape);
        }
    }

    private void refreshPreview() {
        preview.setColor(tintedSurface(activity, selected));
        preview.setStroke(activity.dp(1), borderColor(activity, selected));
    }

    static GradientDrawable frame(MainActivity activity, int value) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(activity.dp(14));
        background.setColor(tintedSurface(activity, value));
        background.setStroke(activity.dp(1), borderColor(activity, value));
        return background;
    }

    private static int tintedSurface(MainActivity activity, int value) {
        return mix(activity.getColor(R.color.vault_surface_soft), borderColor(activity, value), 0.09f);
    }

    static int borderColor(MainActivity activity, int value) {
        if (value >= 0) {
            return switch (value) {
                case 1 -> activity.getColor(R.color.pinbao_group_blue);
                case 2 -> activity.getColor(R.color.pinbao_group_green);
                case 3 -> activity.getColor(R.color.pinbao_group_amber);
                default -> {
                    if (value < 4 || value >= LIGHT.length) yield activity.getColor(R.color.vault_accent);
                    boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                            == Configuration.UI_MODE_NIGHT_YES;
                    yield (dark ? DARK : LIGHT)[value];
                }
            };
        }
        int surface = activity.getColor(R.color.vault_surface_soft);
        int anchor = Color.luminance(surface) < 0.45f ? Color.WHITE : Color.BLACK;
        int adjusted = value;
        for (int step = 1; step <= 10 && contrast(adjusted, mix(surface, adjusted, 0.09f)) < 3; step++) {
            adjusted = mix(value, anchor, step / 10f);
        }
        return adjusted;
    }

    private static float contrast(int a, int b) {
        float first = Color.luminance(a);
        float second = Color.luminance(b);
        return (Math.max(first, second) + .05f) / (Math.min(first, second) + .05f);
    }

    private static int mix(int first, int second, float ratio) {
        return Color.rgb(Math.round(Color.red(first) * (1 - ratio) + Color.red(second) * ratio),
                Math.round(Color.green(first) * (1 - ratio) + Color.green(second) * ratio),
                Math.round(Color.blue(first) * (1 - ratio) + Color.blue(second) * ratio));
    }
}
