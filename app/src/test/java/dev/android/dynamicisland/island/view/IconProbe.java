package dev.android.dynamicisland.island.view;

import android.graphics.Canvas;
import dev.android.dynamicisland.island.model.IslandItem;

/** For tests in other packages: the compact pill's leading icon, as the pill draws it. */
public final class IconProbe {
    private IconProbe() {
    }

    public static void drawLeading(IslandView view, Canvas canvas, IslandItem item, float left, float midY, float size) {
        view.drawLeadingMono(canvas, item, left, midY, size, 1f);
    }
}
