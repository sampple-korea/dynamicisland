package dev.android.dynamicisland.island.view;

import dev.android.dynamicisland.island.model.IslandItem;

/** For tests in other packages: the width the compact pill of an item asks for. */
public final class CompactWidthProbe {
    private CompactWidthProbe() {
    }

    public static float width(IslandView view, IslandItem item) {
        return new CompactContent(view, item).desiredWidth();
    }
}
