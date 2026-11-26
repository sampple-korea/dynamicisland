package dev.android.dynamicisland.island;

import android.graphics.Rect;

/** For tests in other packages: the turned island's centre. */
public final class IslandPlacementProbe {
    private IslandPlacementProbe() {
    }

    public static float[] openCardsOffset(int edge, float holeX, float holeY, float holeR, int screenW, int screenH,
                                          float[] span, float margin) {
        return IslandPlacement.openCardsOffset(edge, holeX, holeY, holeR, screenW, screenH, span, margin);
    }

    public static float[] sidewaysCentre(int edge, Rect hole, int screenWidth, int screenHeight) {
        return IslandPlacement.sidewaysCentre(edge, hole, screenWidth, screenHeight);
    }
}
