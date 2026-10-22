package dev.android.dynamicisland.settings;

import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;
import dev.android.dynamicisland.R;

/**
 * The page where the user picks the island's animations: the smoothed ones of 0.1.4 (default) or the legacy ones of
 * 0.1.3 and before. Two small drawings show the difference, a radio list picks, and nothing changes until Apply.
 * Main thread only.
 */
final class AnimationScreen {
    private final SettingsActivity activity;
    private final OneUi ui;
    private final SharedPreferences prefs;
    /** The choice on screen; it is saved by Apply. */
    private boolean pendingLegacy;

    AnimationScreen(SettingsActivity activity, OneUi ui, SharedPreferences prefs) {
        this.activity = activity;
        this.ui = ui;
        this.prefs = prefs;
    }

    private boolean savedLegacy() {
        return prefs.getBoolean(ModuleSettings.KEY_LEGACY_ANIMATION, false);
    }

    /** Builds the page; the choice starts at what is saved. */
    void open() {
        pendingLegacy = savedLegacy();
        page();
    }

    private void page() {
        LinearLayout page = activity.page();
        activity.subPage(activity.getString(R.string.anim_title));

        LinearLayout previews = ui.card(page);
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER);
        row.setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(20));
        previews.addView(row, new LinearLayout.LayoutParams(-1, -2));
        row.addView(preview(false, R.string.anim_new), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(preview(true, R.string.anim_legacy), new LinearLayout.LayoutParams(0, -2, 1));

        ui.note(page, activity.getString(R.string.anim_pick));

        LinearLayout choices = ui.card(page);
        LinearLayout newRow = choice(choices, R.string.anim_new, R.string.anim_new_desc, !pendingLegacy);
        LinearLayout oldRow = choice(choices, R.string.anim_legacy, R.string.anim_legacy_desc, pendingLegacy);
        newRow.setOnClickListener(v -> select(false));
        oldRow.setOnClickListener(v -> select(true));

        TextView apply = ui.text(activity.getString(R.string.anim_apply), 17, 0xffffffff);
        apply.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        apply.setGravity(Gravity.CENTER);
        apply.setBackground(ui.round(ui.accent, ui.dp(28)));
        boolean changed = pendingLegacy != savedLegacy();
        apply.setAlpha(changed ? 1.0f : 0.4f);
        apply.setEnabled(changed);
        apply.setOnClickListener(v -> save());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(220), ui.dp(54));
        p.topMargin = ui.dp(28);
        p.gravity = Gravity.CENTER_HORIZONTAL;
        page.addView(apply, p);
        ui.note(page, activity.getString(R.string.anim_after));
    }

    private void select(boolean legacy) {
        if (pendingLegacy != legacy) {
            pendingLegacy = legacy;
            activity.page().removeAllViews();
            page();
        }
    }

    private void save() {
        prefs.edit().putBoolean(ModuleSettings.KEY_LEGACY_ANIMATION, pendingLegacy).apply();
        Toast.makeText(activity, R.string.anim_applied, Toast.LENGTH_SHORT).show();
        activity.goBack();
    }

    /** One drawing with its name underneath. */
    private LinearLayout preview(boolean legacy, int label) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.addView(new Sketch(legacy), new LinearLayout.LayoutParams(ui.dp(120), ui.dp(170)));
        TextView name = ui.text(activity.getString(label), 17, ui.accent);
        name.setPadding(0, ui.dp(14), 0, 0);
        name.setGravity(Gravity.CENTER);
        box.addView(name, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /** A row with a radio button, a title and a description. */
    private LinearLayout choice(LinearLayout group, int title, int summary, boolean checked) {
        if (group.getChildCount() > 0) {
            View line = new View(activity);
            line.setBackgroundColor(ui.divider);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1) / 2));
            lp.leftMargin = ui.dp(70);
            lp.rightMargin = ui.dp(22);
            group.addView(line, lp);
        }
        LinearLayout box = new LinearLayout(activity);
        box.setGravity(Gravity.TOP);
        box.setPadding(ui.dp(22), ui.dp(16), ui.dp(22), ui.dp(16));
        RadioButton radio = new RadioButton(activity);
        radio.setChecked(checked);
        radio.setClickable(false);
        radio.setFocusable(false);
        radio.setButtonTintList(ColorStateList.valueOf(checked ? ui.accent : ui.muted));
        box.addView(radio, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(ui.dp(14), 0, 0, 0);
        words.addView(ui.text(activity.getString(title), 18, ui.textColor));
        TextView detail = ui.text(activity.getString(summary), 14, ui.muted);
        detail.setPadding(0, ui.dp(4), 0, 0);
        words.addView(detail);
        box.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        group.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    /**
     * A small drawing of a phone with the pill and two cards. In the new animation the first card is in front and
     * every shape wears an outline; in the legacy one the lower card is in front and there is no outline.
     */
    private final class Sketch extends View {
        private final boolean legacy;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Sketch(boolean legacy) {
            super(activity);
            this.legacy = legacy;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(ui.dark ? 0xff2a2a30 : 0xffe4e4ea);
            r.set(0, 0, w, h);
            c.drawRoundRect(r, w * 0.14f, w * 0.14f, paint);
            float pad = w * 0.1f;
            float pillH = h * 0.075f;
            float top = h * 0.07f;
            // two overlapping cards below the pill; the new animation keeps the first one in front
            float firstTop = top + pillH + h * 0.04f;
            float secondTop = firstTop + h * 0.16f;
            if (legacy) {
                card(c, pad, firstTop, w - pad, firstTop + h * 0.2f, 0xff1c1c20, 0xffffb02e);
                card(c, pad, secondTop, w - pad, secondTop + h * 0.2f, 0xff000000, 0xffff5a8a);
            } else {
                card(c, pad, secondTop, w - pad, secondTop + h * 0.2f, 0xff1c1c20, 0xffff5a8a);
                card(c, pad, firstTop, w - pad, firstTop + h * 0.2f, 0xff000000, 0xffffb02e);
            }
            // the pill
            paint.setColor(0xff000000);
            r.set(w * 0.3f, top, w * 0.7f, top + pillH);
            c.drawRoundRect(r, pillH / 2, pillH / 2, paint);
            if (!legacy) {
                outline(c, r, pillH / 2);
            }
        }

        /** One card; what is drawn later lies on top. */
        private void card(Canvas c, float l, float t, float rr, float b, int body, int mark) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(body);
            r.set(l, t, rr, b);
            float radius = (b - t) * 0.3f;
            c.drawRoundRect(r, radius, radius, paint);
            if (!legacy) {
                outline(c, r, radius);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(mark);
            c.drawRoundRect(l + (rr - l) * 0.12f, t + (b - t) * 0.22f, l + (rr - l) * 0.3f, t + (b - t) * 0.5f, 6, 6, paint);
            paint.setColor(0xffd0d0d8);
            c.drawRoundRect(l + (rr - l) * 0.38f, t + (b - t) * 0.26f, l + (rr - l) * 0.85f, t + (b - t) * 0.36f, 4, 4, paint);
            c.drawRoundRect(l + (rr - l) * 0.38f, t + (b - t) * 0.5f, l + (rr - l) * 0.7f, t + (b - t) * 0.6f, 4, 4, paint);
        }

        private void outline(Canvas c, RectF shape, float radius) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, getWidth() * 0.012f));
            paint.setColor(0x99ffffff);
            c.drawRoundRect(shape, radius, radius, paint);
        }
    }
}
