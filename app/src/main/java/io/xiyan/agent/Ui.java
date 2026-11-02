// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BACKGROUND = Color.rgb(11, 16, 32);
    static final int SURFACE = Color.rgb(21, 30, 49);
    static final int TEXT = Color.rgb(235, 242, 252);
    static final int MUTED = Color.rgb(159, 177, 199);
    static final int ACCENT = Color.rgb(97, 223, 206);
    static final int ALERT = Color.rgb(255, 145, 156);
    private Ui() { }

    static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    static void install(Activity activity, View view) {
        view.setBackgroundColor(BACKGROUND);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        activity.setContentView(view);
        view.requestApplyInsets();
    }
    static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setLineSpacing(dp(activity, 3), 1f);
        view.setPadding(0, dp(activity, 3), 0, dp(activity, 3));
        return view;
    }
    static TextView heading(Activity activity, String value, int size) {
        TextView view = text(activity, value, size, TEXT);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    static LinearLayout card(Activity activity, LinearLayout parent, String eyebrow, String title) {
        LinearLayout card = column(activity);
        int padding = dp(activity, 18);
        card.setPadding(padding, padding, padding, padding);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(SURFACE);
        shape.setCornerRadius(dp(activity, 18));
        card.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(activity, 14), 0, 0);
        parent.addView(card, params);
        card.addView(text(activity, eyebrow, 11, ACCENT));
        card.addView(heading(activity, title, 20));
        return card;
    }
    static Button button(Activity activity, LinearLayout parent, String label, boolean danger, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(danger ? ALERT : ACCENT);
        button.setMinHeight(dp(activity, 48));
        button.setOnClickListener(view -> action.run());
        parent.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }
}
