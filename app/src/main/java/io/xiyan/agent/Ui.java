// SPDX-License-Identifier: GPL-3.0-only
package io.xiyan.agent;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native UI primitives. All bitmap-free shapes are original project artwork. */
final class Ui {
    static final int BACKGROUND = Color.rgb(12, 18, 24);
    static final int SURFACE = Color.rgb(23, 32, 41);
    static final int TEXT = Color.rgb(238, 244, 246);
    static final int MUTED = Color.rgb(166, 182, 190);
    static final int ACCENT = Color.rgb(116, 230, 205);
    static final int ALERT = Color.rgb(255, 161, 168);
    static final class Colors {
        final int background, surface, raised, text, muted, accent, accentSurface, border, alert, alertSurface;
        Colors(int background, int surface, int raised, int text, int muted, int accent,
                int accentSurface, int border, int alert, int alertSurface) {
            this.background = background; this.surface = surface; this.raised = raised;
            this.text = text; this.muted = muted; this.accent = accent;
            this.accentSurface = accentSurface; this.border = border;
            this.alert = alert; this.alertSurface = alertSurface;
        }
    }
    private static final Colors DARK = new Colors(BACKGROUND, SURFACE, 0xff202e39, TEXT, MUTED,
            ACCENT, 0xff173d39, 0xff30414b, ALERT, 0xff45282e);
    private static final Colors LIGHT = new Colors(0xfff3f7f7, 0xffffffff, 0xffe8f0f0,
            0xff182d34, 0xff536a73, 0xff006c5b, 0xffd4f5eb, 0xffd5e1e2,
            0xffa5243e, 0xffffe8ec);
    private Ui() { }

    static String themeChoice(Context context) {
        return context.getSharedPreferences("xiyan_appearance", Context.MODE_PRIVATE).getString("theme", "system");
    }
    static boolean isLight(Activity activity) {
        // The control lab keeps its established dark test surface and capture semantics.
        if (activity instanceof TestPadActivity) return false;
        String choice = themeChoice(activity);
        return "light".equals(choice) || ("system".equals(choice)
                && (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES);
    }
    static Colors colors(Activity activity) { return isLight(activity) ? LIGHT : DARK; }
    static void applyTheme(Activity activity) {
        activity.setTheme(isLight(activity) ? R.style.XiyanLightTheme : R.style.XiyanDarkTheme);
    }
    static int dp(Activity activity, float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    static LinearLayout column(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    static LinearLayout row(Activity activity) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    @SuppressWarnings("deprecation")
    static void install(Activity activity, View view) {
        Colors palette = colors(activity);
        view.setBackgroundColor(palette.background);
        // Install the decor before requesting its controller during a cold Activity launch.
        activity.setContentView(view);
        activity.getWindow().setStatusBarColor(palette.background);
        activity.getWindow().setNavigationBarColor(palette.surface);
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(isLight(activity)
                    ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            activity.getWindow().getDecorView().setSystemUiVisibility(isLight(activity)
                    ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        }
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        view.requestApplyInsets();
    }
    private static int resolve(Activity activity, int value) {
        Colors c = colors(activity);
        if (value == TEXT) return c.text;
        if (value == MUTED) return c.muted;
        if (value == ACCENT) return c.accent;
        if (value == ALERT) return c.alert;
        return value;
    }
    static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(resolve(activity, color));
        view.setTextSize(size);
        view.setFontFeatureSettings("kern");
        view.setLineSpacing(dp(activity, 3), 1f);
        view.setPadding(0, dp(activity, 3), 0, dp(activity, 3));
        return view;
    }
    static TextView heading(Activity activity, String value, int size) {
        TextView view = text(activity, value, size, TEXT);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        view.setAccessibilityHeading(true);
        return view;
    }
    static GradientDrawable shape(Activity activity, int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(activity, radius));
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(activity, 1), stroke);
        return shape;
    }
    static LinearLayout surface(Activity activity, LinearLayout parent, int fill) {
        LinearLayout card = column(activity);
        int padding = dp(activity, 20);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(shape(activity, fill, 24, colors(activity).border));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(activity, 14), 0, 0);
        parent.addView(card, params);
        return card;
    }
    static LinearLayout card(Activity activity, LinearLayout parent, String eyebrow, String title) {
        LinearLayout card = surface(activity, parent, colors(activity).surface);
        if (!eyebrow.isEmpty()) card.addView(text(activity, eyebrow, 12, ACCENT));
        card.addView(heading(activity, title, 20));
        return card;
    }
    static Button action(Activity activity, String label, boolean primary, boolean danger, Runnable action) {
        Colors c = colors(activity);
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int fill = danger ? c.alertSurface : primary ? c.accent : c.raised;
        int foreground = danger ? c.alert : primary ? (isLight(activity) ? Color.WHITE : 0xff10342d) : c.text;
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{c.muted, foreground}));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(isLight(activity) ? 0x22006c5b : 0x3374e6cd),
                shape(activity, fill, 16, Color.TRANSPARENT), null));
        button.setMinHeight(dp(activity, 50));
        button.setMinimumHeight(dp(activity, 50));
        button.setMinWidth(dp(activity, 48));
        button.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        button.setOnClickListener(view -> action.run());
        return button;
    }
    static Button button(Activity activity, LinearLayout parent, String label, boolean danger, Runnable action) {
        Button button = action(activity, label, false, danger, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(activity, 10), 0, 0);
        parent.addView(button, params);
        return button;
    }
    static TextView badge(Activity activity, String label) {
        TextView text = text(activity, label, 12, ACCENT);
        text.setBackground(shape(activity, colors(activity).accentSurface, 40, Color.TRANSPARENT));
        text.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
        return text;
    }
    static void gap(Activity activity, LinearLayout parent, int height) {
        parent.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(activity, height)));
    }
}
