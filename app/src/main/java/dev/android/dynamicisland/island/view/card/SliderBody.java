package dev.android.dynamicisland.island.view.card;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.StaticLayout;
import dev.android.dynamicisland.island.graphics.Colors;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandView;

/**
 * A card with one big slider in the style of the iOS control centre: a fat rounded track filled with white up to the
 * value, the item's icon on the filled part, and the action buttons (turn off) below. Used for the flashlight's
 * brightness. While the slider is dragged the view keeps this body in {@code owner.sliderBody} and the dragged
 * position in {@code owner.sliderFraction}, which is shown instead of the item's value.
 */
public final class SliderBody extends CardBody {
    private static final float TILE_DP = 40.0f;
    private static final float TRACK_HEIGHT_DP = 52.0f;
    private static final float DRAG_GROW = 1.05f;
    private static final float ICON_DP = 24.0f;
    private static final float ROW_SLIDE_DP = 8.0f;
    private static final int ICON_ON_FILL = 0xFF2C2C2E;
    private static final float HIT_EXTRA_DP = 14.0f;
    private static final int STEP_DOTS_MAX = 10;
    private static final float DOT_INSET_DP = 14.0f;
    /** How long the bar takes to glide to its step, and the shape of that glide (a damped swing). */
    public static final float SETTLE_MS = 380.0f;
    private static final float SETTLE_DAMPING = 7.0f;
    private static final float SETTLE_SWING = 10.0f;

    final float pad;
    final StaticLayout title;
    final StaticLayout subtitle;
    final float trackLeft;
    final float trackRight;
    final float trackTop;
    final float trackH;
    private final Path clip = new Path();

    public SliderBody(IslandView owner, IslandItem item, float w) {
        super(owner, item, w);
        pad = owner.dp(18.0f);
        float tile = owner.dp(TILE_DP);
        int textWidth = Math.max(1, Math.round(w - (pad * 2.0f) - tile - owner.dp(14.0f) - owner.dp(56.0f)));
        title = item.title == null ? null : IslandView.layout(item.title, owner.mediaTitlePaint(), textWidth, 1);
        subtitle = item.subtitle == null ? null : IslandView.layout(item.subtitle, owner.mediaArtistPaint(), textWidth, 1);
        trackH = owner.dp(TRACK_HEIGHT_DP);
        trackTop = pad + tile + owner.dp(16.0f);
        trackLeft = pad;
        trackRight = w - pad;
        float buttonsTop = trackTop + trackH + owner.dp(14.0f);
        float buttonRow = layoutTextButtons(buttonsTop, pad);
        h = (buttonRow > 0.0f ? buttonsTop + buttonRow : trackTop + trackH) + pad;
    }

    /** Whether ({@code lx}, {@code ly}) in body coordinates is on the slider and the item has one. */
    public boolean onSlider(float lx, float ly) {
        float extra = owner.dp(HIT_EXTRA_DP);
        return item.sliderChange != null && ly >= trackTop - extra && ly <= trackTop + trackH + extra
                && lx >= trackLeft - extra && lx <= trackRight + extra;
    }

    /**
     * The value after the finger moved {@code dx} pixels since it touched down at {@code startValue}. The drag is
     * relative, as in the iOS control centre: touching the bar does not make it jump, and the edge of the fill stays
     * right under the finger (the fill has the track's height as its minimum width, which is left out of the range).
     */
    public float valueAfterDrag(float startValue, float dx) {
        return IslandView.clamp01(startValue + (dx / Math.max(1.0f, trackRight - trackLeft - trackH)));
    }

    /** The value the bar shows right now: the dragged one while dragging, else where it rests or glides to. */
    public float shownValue() {
        return owner.sliderBody == this ? owner.sliderFraction : settledValue();
    }

    /** The step the finger position {@code fraction} stands for (the value itself when the slider has no steps). */
    public float snapped(float fraction) {
        return snap(fraction, item.sliderSteps);
    }

    /**
     * Where the bar is drawn after the finger left it: it glides from where it was released to the nearest step and
     * overshoots a hair on the way, so it feels pulled in by a magnet.
     */
    private float settledValue() {
        float target = snap(IslandView.clamp01(item.sliderValue), item.sliderSteps);
        if (!item.key.equals(owner.sliderSettleKey)) {
            return target;
        }
        float t = (IslandView.now() - owner.sliderSettleStart) / SETTLE_MS;
        if (t >= 1.0f) {
            return target;
        }
        float e = 1.0f - (float) (Math.exp(-SETTLE_DAMPING * t) * Math.cos(SETTLE_SWING * t));
        return IslandView.clamp01(owner.sliderSettleFrom + ((owner.sliderSettleTo - owner.sliderSettleFrom) * e));
    }

    static float snap(float fraction, int steps) {
        if (steps < 2) {
            return fraction;
        }
        return Math.round(fraction * (steps - 1)) / (float) (steps - 1);
    }

    @Override
    public void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        float topRow = rowAlpha(elapsed, 0);
        float topDy = (1.0f - topRow) * owner.dp(ROW_SLIDE_DP);
        drawHeader(c, x, y + topDy, alpha * topRow);
        float sliderRow = rowAlpha(elapsed, 1);
        drawSlider(c, x, y + ((1.0f - sliderRow) * owner.dp(ROW_SLIDE_DP)), alpha * sliderRow);
        float buttonsRow = rowAlpha(elapsed, 2);
        drawButtons(c, x, y + ((1.0f - buttonsRow) * owner.dp(ROW_SLIDE_DP)), alpha * buttonsRow);
    }

    private void drawHeader(Canvas c, float x, float y, float alpha) {
        float tile = owner.dp(TILE_DP);
        owner.tmpRect.set(x + pad, y + pad, x + pad + tile, y + pad + tile);
        int accent = item.accent == -1 ? Colors.WHITE : item.accent;
        owner.fillPaint.setColor(Colors.withAlpha(accent, alpha));
        c.drawRoundRect(owner.tmpRect, tile * 0.32f, tile * 0.32f, owner.fillPaint);
        if (item.icon != null) {
            float size = tile * 0.6f;
            owner.drawDrawable(c, item.icon, false, ICON_ON_FILL, x + pad + ((tile - size) / 2.0f), y + pad + ((tile - size) / 2.0f), size, alpha, 0.0f);
        }
        float textX = x + pad + tile + owner.dp(14.0f);
        float textHeight = (title != null ? title.getHeight() : 0) + (subtitle != null ? owner.dp(2.0f) + subtitle.getHeight() : 0.0f);
        float lineY = y + pad + ((tile - textHeight) / 2.0f);
        if (title != null) {
            owner.mediaTitlePaint().setColor(Colors.withAlpha(Colors.WHITE, alpha));
            IslandView.drawLayout(c, title, textX, lineY);
            lineY += title.getHeight() + owner.dp(2.0f);
        }
        if (subtitle != null) {
            owner.mediaArtistPaint().setColor(Colors.withAlpha(Colors.TEXT_ARTIST, alpha));
            IslandView.drawLayout(c, subtitle, textX, lineY);
        }
        if (item.trailingText != null) {
            owner.smallPaint.setColor(Colors.withAlpha(Colors.TEXT_SECONDARY, alpha));
            String text = item.trailingText.toString();
            Paint.FontMetrics fm = owner.smallPaint.getFontMetrics();
            c.drawText(text, x + w - pad - owner.smallPaint.measureText(text), y + pad + (tile / 2.0f) - ((fm.ascent + fm.descent) / 2.0f), owner.smallPaint);
        }
    }

    /** Small dots where the steps are, so the levels can be seen on the bar. */
    private void drawStepDots(Canvas c, float left, float right, float top, float height, float fillRight, float alpha) {
        int steps = item.sliderSteps;
        if (steps < 2 || steps > STEP_DOTS_MAX) {
            return;
        }
        float radius = owner.dp(2.5f);
        for (int i = 1; i < steps; i++) {
            // a dot sits just inside the end of the fill the bar has at that step, so the fill's edge always rests at one
            float edge = left + height + ((i / (float) (steps - 1)) * ((right - left) - height));
            float x = edge - owner.dp(DOT_INSET_DP);
            boolean covered = edge <= fillRight + 0.5f;
            owner.fillPaint.setColor(Colors.withAlpha(covered ? ICON_ON_FILL : Colors.WHITE, (covered ? 0.28f : 0.45f) * alpha));
            c.drawCircle(x, top + (height / 2.0f), radius, owner.fillPaint);
        }
    }

    private void drawSlider(Canvas c, float x, float y, float alpha) {
        boolean dragged = owner.sliderBody == this;
        float value = dragged ? owner.sliderFraction : settledValue();
        float left = x + trackLeft;
        float right = x + trackRight;
        float top = y + trackTop;
        float height = trackH;
        int save = c.save();
        if (dragged) {
            // the whole bar swells a little under the finger, as in the iOS control centre
            c.scale(DRAG_GROW, DRAG_GROW, (left + right) / 2.0f, top + (height / 2.0f));
        }
        RectF track = owner.tmpRect;
        track.set(left, top, right, top + height);
        float radius = height / 2.0f;
        owner.fillPaint.setColor(Colors.withAlpha(Colors.SEEK_TRACK, alpha));
        c.drawRoundRect(track, radius, radius, owner.fillPaint);
        clip.reset();
        clip.addRoundRect(track, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        float fillRight = left + height + (value * ((right - left) - height));
        owner.fillPaint.setColor(Colors.withAlpha(Colors.WHITE, 0.94f * alpha));
        c.drawRect(left, top, fillRight, top + height, owner.fillPaint);
        drawStepDots(c, left, right, top, height, fillRight, alpha);
        c.restore();
        if (item.icon != null) {
            float size = owner.dp(ICON_DP);
            owner.drawDrawable(c, item.icon, false, ICON_ON_FILL, left + ((height - size) / 2.0f), top + ((height - size) / 2.0f), size, alpha, 0.0f);
        }
        c.restoreToCount(save);
    }
}
