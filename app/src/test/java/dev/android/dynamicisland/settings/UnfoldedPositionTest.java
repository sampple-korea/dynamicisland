package dev.android.dynamicisland.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.content.SharedPreferences;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** A foldable's unfolded screen can keep its own island position and camera calibration. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = RuntimeRegressionTest.EmptyXPrefs.class)
public class UnfoldedPositionTest {
    private final SharedPreferences prefs = ModuleSettings.openStore(RuntimeEnvironment.getApplication());

    @After public void reset() {
        prefs.edit().clear().commit();
        readAsSystemUi();
        ModuleSettings.setLargeScreen(false);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "started", false);
    }

    private void load(boolean separate) {
        prefs.edit().clear()
                .putBoolean(ModuleSettings.KEY_SEPARATE_UNFOLDED_POSITION, separate)
                .putInt(ModuleSettings.KEY_PILL_X_DP, 10).putInt(ModuleSettings.KEY_PILL_Y_DP, 2)
                .putInt(ModuleSettings.KEY_HOLE_MODE, ModuleSettings.HOLE_MANUAL).putInt(ModuleSettings.KEY_HOLE_X_DP, 4)
                .putInt(ModuleSettings.KEY_PILL_X_DP + ModuleSettings.UNFOLDED_SUFFIX, -30)
                .putInt(ModuleSettings.KEY_HOLE_X_DP + ModuleSettings.UNFOLDED_SUFFIX, 40)
                .commit();
        readAsSystemUi();
    }

    /** Loads the settings the way System UI does (through the settings provider), placement included. */
    private static void readAsSystemUi() {
        Robolectric.buildContentProvider(SettingsProvider.class).create(SettingsProvider.AUTHORITY);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "started", true);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "prefs", null);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "loaded", false);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "viaProvider", false);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "lastRemote", null);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "previewUntil", 0L);
        ReflectionHelpers.callStaticMethod(ModuleSettings.class, "refresh");
    }

    @Test public void theUnfoldedScreenUsesItsOwnValuesAndFallsBackToTheSharedOnes() {
        load(true);
        ModuleSettings.setLargeScreen(false);
        assertEquals(10, ModuleSettings.pillXDp());
        assertEquals(4, ModuleSettings.holeXDp());
        ModuleSettings.setLargeScreen(true);
        assertEquals(-30, ModuleSettings.pillXDp());
        assertEquals(40, ModuleSettings.holeXDp());
        assertEquals("never set for the unfolded screen: the shared value", 2, ModuleSettings.pillYDp());
        assertEquals(ModuleSettings.HOLE_MANUAL, ModuleSettings.holeMode());
    }

    @Test public void withTheOptionOffEveryScreenSharesOnePosition() {
        load(false);
        ModuleSettings.setLargeScreen(true);
        assertEquals(10, ModuleSettings.pillXDp());
        assertEquals(4, ModuleSettings.holeXDp());
    }

    @Test public void aPreviewFromTheUnfoldedScreenMovesOnlyItsValues() {
        load(true);
        Intent preview = new Intent(ModuleSettings.ACTION_PILL_PREVIEW)
                .putExtra(ModuleSettings.KEY_PILL_X_DP, 55).putExtra(ModuleSettings.EXTRA_PREVIEW_UNFOLDED, true);
        ModuleSettings.applyPlacementPreview(preview);
        ModuleSettings.setLargeScreen(true);
        assertEquals(55, ModuleSettings.pillXDp());
        ModuleSettings.setLargeScreen(false);
        assertEquals("the folded screen did not move", 10, ModuleSettings.pillXDp());
        ModuleSettings.applyPlacementPreview(new Intent(ModuleSettings.ACTION_PILL_PREVIEW)
                .putExtra(ModuleSettings.KEY_PILL_X_DP, -12).putExtra(ModuleSettings.EXTRA_ACTIVE, false));
        assertEquals(-12, ModuleSettings.pillXDp());
        ModuleSettings.setLargeScreen(true);
        assertEquals("and the unfolded one keeps its own", 55, ModuleSettings.pillXDp());
    }

    @Test public void aPreviewRightAfterTheSwitchIsTurnedOnMovesTheIslandAtOnce() {
        load(false); // System UI has not read the switch from the file yet
        ModuleSettings.setLargeScreen(true);
        ModuleSettings.applyPlacementPreview(new Intent(ModuleSettings.ACTION_PILL_PREVIEW)
                .putExtra(ModuleSettings.KEY_PILL_X_DP, 70).putExtra(ModuleSettings.EXTRA_PREVIEW_UNFOLDED, true));
        assertEquals(70, ModuleSettings.pillXDp());
        ModuleSettings.setLargeScreen(false);
        assertEquals(10, ModuleSettings.pillXDp());
    }

    @Test public void theLargeScreenThresholdIsTheShortestSide() {
        assertTrue(ModuleSettings.isLargeScreen(1812, 2176, 2.625f)); // an unfolded inner screen, about 690 dp
        assertFalse(ModuleSettings.isLargeScreen(904, 2316, 2.625f)); // its cover screen, about 344 dp
        assertFalse("landscape on a phone is not large", ModuleSettings.isLargeScreen(2340, 1080, 2.75f));
    }

    @Test @Config(qualifiers = "w690dp-h830dp")
    public void thePageOpenOnTheUnfoldedScreenSavesItsOwnKeys() {
        prefs.edit().clear().putBoolean(ModuleSettings.KEY_SEPARATE_UNFOLDED_POSITION, true).putInt(ModuleSettings.KEY_PILL_X_DP, 10).commit();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PositionScreen screen = ReflectionHelpers.getField(activity, "position");
        SharedPreferences p = ModuleSettings.openStore(activity);
        assertTrue(screen.editsUnfolded());
        assertEquals(ModuleSettings.KEY_PILL_X_DP + ModuleSettings.UNFOLDED_SUFFIX, screen.storedKey(ModuleSettings.KEY_PILL_X_DP));
        assertEquals("sizes are shared", ModuleSettings.KEY_PILL_H_PCT, screen.storedKey(ModuleSettings.KEY_PILL_H_PCT));
        p.edit().putInt(ModuleSettings.KEY_PILL_X_DP + ModuleSettings.UNFOLDED_SUFFIX, 33).commit();
        screen.resetPosition();
        assertFalse(p.contains(ModuleSettings.KEY_PILL_X_DP + ModuleSettings.UNFOLDED_SUFFIX));
        assertEquals("resetting the unfolded screen keeps the folded one", 10, p.getInt(ModuleSettings.KEY_PILL_X_DP, 0));
    }

    @Test @Config(qualifiers = "w360dp-h800dp")
    public void thePageOpenOnTheFoldedScreenSavesTheSharedKeys() {
        prefs.edit().clear().putBoolean(ModuleSettings.KEY_SEPARATE_UNFOLDED_POSITION, true).commit();
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        PositionScreen screen = ReflectionHelpers.getField(activity, "position");
        assertFalse(screen.editsUnfolded());
        assertEquals(ModuleSettings.KEY_PILL_X_DP, screen.storedKey(ModuleSettings.KEY_PILL_X_DP));
    }
}
