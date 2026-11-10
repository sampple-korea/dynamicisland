package dev.android.dynamicisland.island.view;

import static org.junit.Assert.assertEquals;
import dev.android.dynamicisland.island.model.IslandItem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AppGlowColorsTest {
    @Test public void resolvesKnownAppsAndSafeFallback() {
        assertEquals(0xFF25D366, AppGlowColors.getColor("com.whatsapp"));
        assertEquals(0xFF25D366, AppGlowColors.getColor("com.whatsapp.w4b"));
        assertEquals(0xFFB04CEB, AppGlowColors.getColor("com.instagram.android"));
        assertEquals(0xFF39A8F5, AppGlowColors.getColor("org.telegram.messenger"));
        IslandItem item = new IslandItem("unknown");
        item.packageName = "example.unknown";
        assertEquals(0xFFFFFFFF, AppGlowColors.getColor(item));
        item.accent = 0xFF123456;
        assertEquals(0xFF123456, AppGlowColors.getColor(item));
    }
}
