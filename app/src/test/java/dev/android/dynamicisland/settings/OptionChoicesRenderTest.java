package dev.android.dynamicisland.settings;

import android.content.SharedPreferences;
import dev.android.dynamicisland.testutil.RenderCapture;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** The pages with the reworked options: notification modes, events (unlock, charging ring), behavior (landscape). */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "ko-rKR-w360dp-h1600dp-xxhdpi")
public class OptionChoicesRenderTest {
    @Category(RenderCapture.class) @Test public void reworkedChoicesRender() throws Exception {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        SharedPreferences p = ReflectionHelpers.getField(a, "prefs");
        p.edit().putBoolean(ModuleSettings.KEY_HEADS_UP_ISLAND, true).putBoolean(ModuleSettings.KEY_ALL_NOTIFICATIONS, true)
                .putBoolean(ModuleSettings.KEY_SILENT_NOTIFICATION_CARDS, true).putBoolean(ModuleSettings.KEY_UNLOCK_NOTICE, true)
                .putBoolean(ModuleSettings.KEY_UNLOCK_ICON_ONLY, true).putBoolean(ModuleSettings.KEY_CHARGING_PERCENT_RING, true)
                .putBoolean(ModuleSettings.KEY_SHOW_IN_LANDSCAPE, true).putBoolean(ModuleSettings.KEY_LANDSCAPE_AT_CAMERA, true).commit();
        a.open(() -> ReflectionHelpers.callInstanceMethod(a, "notificationsPage"));
        SettingsRenderTest.render(a, "options_notifications");
        a.open(() -> ReflectionHelpers.callInstanceMethod(a, "eventsPage"));
        SettingsRenderTest.render(a, "options_events");
        a.open(() -> ReflectionHelpers.callInstanceMethod(a, "behaviorPage"));
        SettingsRenderTest.render(a, "options_behavior");
    }
}
