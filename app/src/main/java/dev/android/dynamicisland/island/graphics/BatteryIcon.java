package dev.android.dynamicisland.island.graphics;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Circular battery pictogram. Other island pictograms retain their existing renderer. */
public final class BatteryIcon {
    private BatteryIcon() { }

    public static Drawable load(Context host, int percent, boolean charging) {
        return load(host, percent, charging, 0);
    }

    /** {@code chargingState} is a {@code ChargingState} value; it picks the glyph in the middle while charging. */
    public static Drawable load(Context host, int percent, boolean charging, int chargingState) {
        dev.android.dynamicisland.xposed.Diagnostics.fact("battery icon source", "circular gauge");
        return new Ring(Math.max(0, Math.min(100, percent)), charging, chargingState);
    }

    private static final class Ring extends Drawable {
        private final BatteryGauge gauge = new BatteryGauge();
        private final int level;
        private final boolean charging;
        private final int chargingState;
        private int alpha = 255;
        Ring(int level, boolean charging, int chargingState) {
            this.level = level;
            this.charging = charging;
            this.chargingState = chargingState;
        }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            gauge.draw(canvas, b.exactCenterX(), b.exactCenterY(), Math.min(b.width(), b.height()), level,
                    charging ? 0xFF30D158 : level <= 20 ? Colors.DESTRUCTIVE_RED : Colors.WHITE,
                    alpha / 255f, charging ? BatteryGauge.centreFor(chargingState,
                            dev.android.dynamicisland.settings.ModuleSettings.chargingPercentInRing()) : BatteryGauge.CENTRE_NONE);
        }
        @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { gauge.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 24; }
    }
}
