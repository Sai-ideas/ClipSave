package dev.local.clipvault;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DraftSheet {
    final LinearLayout body;
    final Button apply;
    private final AlertDialog dialog;

    DraftSheet(MainActivity activity, String title, String action, boolean sensitive) {
        LinearLayout root = activity.column();
        root.setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(12));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(activity.getColor(R.color.vault_surface));
        float corner = activity.dp(24);
        surface.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        root.setBackground(surface);

        int ink = activity.getColor(R.color.vault_text);
        int accent = activity.getColor(R.color.vault_accent);
        TextView heading = activity.text(title, 20, ink, true);
        heading.setAccessibilityHeading(true);
        root.addView(heading, activity.margins(-1, -2, 0, 8));

        body = activity.column();
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout controls = activity.row();
        Button cancel = activity.button(action == null ? "關閉" : "取消",
                activity.getColor(R.color.vault_surface_soft), ink, this::dismiss);
        controls.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        if (action == null) {
            apply = null;
        } else {
            apply = activity.button(action, accent, activity.getColor(R.color.vault_on_accent), () -> {});
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, -2, 1);
            layout.setMarginStart(activity.dp(8));
            controls.addView(apply, layout);
        }
        root.addView(controls, activity.margins(-1, -2, 8, 0));

        int viewport = activity.getWindow().getDecorView().getHeight();
        int height = Math.min(activity.dp(560), (int) (viewport * 0.82f));
        dialog = activity.show(new AlertDialog.Builder(activity).setView(root), sensitive);
        Window window = dialog.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    void dismiss() { dialog.dismiss(); }
}
