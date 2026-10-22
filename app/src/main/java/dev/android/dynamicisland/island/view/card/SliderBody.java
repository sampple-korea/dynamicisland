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

    /** Position on the slider (0..1) for a touch at {@code lx}, snapped to the steps when the item has some. */
    public float fractionAt(float lx) {
        float f = IslandView.clamp01((lx - trackLeft) / Math.max(1.0f, trackRight - trackLeft));
        return snap(f, item.sliderSteps);
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

    private void drawSlider(Canvas c, float x, float y, float alpha) {
        boolean dragged = owner.sliderBody == this;
        float value = dragged ? owner.sliderFraction : snap(IslandView.clamp01(item.sliderValue), item.sliderSteps);
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
        c.restore();
        if (item.icon != null) {
            float size = owner.dp(ICON_DP);
            owner.drawDrawable(c, item.icon, false, ICON_ON_FILL, left + ((height - size) / 2.0f), top + ((height - size) / 2.0f), size, alpha, 0.0f);
        }
        c.restoreToCount(save);
    }
}
