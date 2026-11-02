package dev.android.dynamicisland.island.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.android.dynamicisland.island.model.IslandItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "mdpi")
public class FullWidthPillTest {
    @Test
    public void aFullWidthItemTakesTheWidestPillAndOthersKeepTheirOwnWidth() {
        IslandView view = new IslandView(RuntimeEnvironment.getApplication(), IslandHost.NONE);
        view.setGeometry(360, 180, 35, 22, false);
        IslandItem plain = new IslandItem("plain");
        plain.trailing = IslandItem.Trailing.TEXT;
        plain.trailingText = "54%";
        IslandItem wide = new IslandItem("wide");
        wide.trailing = IslandItem.Trailing.TEXT;
        wide.trailingText = "54%";
        wide.fullWidth = true;
        float own = new CompactContent(view, plain).desiredWidth();
        float widest = new CompactContent(view, wide).desiredWidth();
        assertTrue("the plain pill is narrower than the widest it may be", own < widest);
        assertEquals(view.maxPillW, widest, 0.01f);
    }
}
