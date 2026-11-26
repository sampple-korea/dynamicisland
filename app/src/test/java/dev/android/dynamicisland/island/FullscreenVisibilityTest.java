package dev.android.dynamicisland.island;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import dev.android.dynamicisland.R;
import dev.android.dynamicisland.island.feedback.AudioLevels;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.island.window.IslandWindow;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.settings.SettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
public class FullscreenVisibilityTest {
    @Test public void fullscreenOptInKeepsPortraitContentButRespectsLockAodAndRotation() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        SharedPreferences prefs = ModuleSettings.openStore(activity);
        ModuleSettings.configurePreview(prefs);
        ReflectionHelpers.callInstanceMethod(activity, "behaviorPage");
        Switch option = findSwitch(activity.findViewById(android.R.id.content), activity.getString(R.string.show_in_fullscreen));
        assertNotNull(option);
        assertFalse(option.isChecked());

        IslandPlacement placement = new IslandPlacement(activity, new Handler(Looper.getMainLooper()),
                getClass().getClassLoader(), IslandHost.NONE, new IslandWindow.Listener() {
                    @Override public void onWindowConfigurationChanged() { }
                    @Override public void onWindowInsetsChanged() { }
                }, new AudioLevels());
        placement.ensureWindow(true);
        IslandView view = placement.view();
        assertNotNull(view);
        placement.setStatusBarWindowState(2);
        assertTrue(view.isSuppressedNow());

        option.performClick();
        assertTrue(prefs.getBoolean(ModuleSettings.KEY_SHOW_IN_FULLSCREEN, false));
        ModuleSettings.configurePreview(prefs);
        placement.updateGeometry();
        assertFalse(view.isSuppressedNow());

        placement.setBarState(1);
        assertTrue(view.isSuppressedNow());
        placement.setBarState(0);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(201));
        assertFalse(view.isSuppressedNow());
        placement.setDozing(true);
        assertTrue(view.isSuppressedNow());
        placement.setDozing(false);
        assertTrue("AOD exit waits for the same safe reveal as unlock", view.isSuppressedNow());
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(201));
        assertFalse(view.isSuppressedNow());

        RuntimeEnvironment.setQualifiers("w800dp-h360dp-land-mdpi");
        placement.updateGeometry();
        assertTrue(view.isSuppressedNow());
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-port-mdpi");
        placement.updateGeometry();
        assertFalse(view.isSuppressedNow());

        option.performClick();
        ModuleSettings.configurePreview(prefs);
        placement.updateGeometry();
        assertTrue(view.isSuppressedNow());
        placement.setStatusBarWindowState(0);
        assertFalse(view.isSuppressedNow());
    }

    @Test public void landscapeOptInKeepsTheIslandInLandscapeAndInFullScreenGamesButNotOnTheLockScreen() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        SharedPreferences prefs = ModuleSettings.openStore(activity);
        ModuleSettings.configurePreview(prefs);
        ReflectionHelpers.callInstanceMethod(activity, "behaviorPage");
        assertFalse(prefs.getBoolean(ModuleSettings.KEY_SHOW_IN_LANDSCAPE, false));

        IslandPlacement placement = new IslandPlacement(activity, new Handler(Looper.getMainLooper()),
                getClass().getClassLoader(), IslandHost.NONE, new IslandWindow.Listener() {
                    @Override public void onWindowConfigurationChanged() { }
                    @Override public void onWindowInsetsChanged() { }
                }, new AudioLevels());
        placement.ensureWindow(true);
        IslandView view = placement.view();
        RuntimeEnvironment.setQualifiers("w800dp-h360dp-land-mdpi");
        placement.updateGeometry();
        assertTrue("hidden in landscape by default", view.isSuppressedNow());

        chooseLandscape(activity, 1); // show at the top
        assertTrue(prefs.getBoolean(ModuleSettings.KEY_SHOW_IN_LANDSCAPE, false));
        ModuleSettings.configurePreview(prefs);
        placement.updateGeometry();
        assertFalse("shown in landscape", view.isSuppressedNow());
        placement.setStatusBarWindowState(2);
        assertFalse("shown in a landscape full-screen game", view.isSuppressedNow());
        placement.setBarState(1);
        assertTrue("still hidden on the lock screen", view.isSuppressedNow());
        placement.setBarState(0);

        RuntimeEnvironment.setQualifiers("w360dp-h800dp-port-mdpi");
        placement.updateGeometry();
        assertTrue("portrait full screen still follows its own option", view.isSuppressedNow());
    }

    /** Opens the landscape choice row on the behavior page and picks {@code selection}. */
    private static void chooseLandscape(SettingsActivity activity, int selection) {
        View label = findText(activity.findViewById(android.R.id.content), activity.getString(R.string.show_in_landscape));
        assertNotNull(label);
        ((View) label.getParent().getParent()).performClick();
        android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        dialog.getListView().performItemClick(null, selection, selection);
    }

    private static View findText(View view, String text) {
        if (view instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Switch findSwitch(View view, String title) {
        if (view instanceof Switch && title.contentEquals(view.getContentDescription())) return (Switch) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Switch found = findSwitch(group.getChildAt(i), title);
                if (found != null) return found;
            }
        }
        return null;
    }
}
