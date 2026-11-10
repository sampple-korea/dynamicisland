package dev.android.dynamicisland.island.view;

import android.graphics.RectF;

/** Geometry shared by the future surface and its hit region; callers own all rectangles. */
final class NotificationMorphGeometry {
    private NotificationMorphGeometry() {}

    static void interpolate(RectF from, RectF to, float progress, RectF out) {
        float p = Math.max(0f, Math.min(1f, progress));
        out.set(from.left + (to.left - from.left) * p,
                from.top + (to.top - from.top) * p,
                from.right + (to.right - from.right) * p,
                from.bottom + (to.bottom - from.bottom) * p);
    }

    static void expanded(RectF origin, float screenWidth, float margin, float width,
                         float contentHeight, float cameraClearance, RectF out) {
        float safeWidth = Math.max(0, screenWidth - 2 * margin);
        float w = Math.min(Math.max(0, width), safeWidth);
        float left = Math.max(margin, Math.min(origin.centerX() - w / 2, screenWidth - margin - w));
        float top = origin.top;
        float h = Math.max(origin.height(), Math.max(0, cameraClearance) + Math.max(0, contentHeight));
        out.set(left, top, left + w, top + h);
    }
}
