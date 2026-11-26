package dev.android.dynamicisland.island.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import dev.android.dynamicisland.island.IslandPlacementProbe;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.testutil.RenderCapture;
import java.io.File;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/** #55: in landscape the island is drawn turned at a camera on the side of the screen. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
public class SidewaysIslandTest {
    private static final int SCREEN_W = 800;  // landscape, long side
    private static final int SCREEN_H = 360;  // short side
    private static final int EXTENT = 120;    // the island's height, which becomes the window's width

    private IslandWindow window() {
        ModuleSettings.configurePreview(ModuleSettings.openStore(RuntimeEnvironment.getApplication()));
        IslandWindow window = new IslandWindow(RuntimeEnvironment.getApplication(), IslandHost.NONE, null);
        window.setWindowHeight(EXTENT);
        return window;
    }

    private static View root(IslandWindow window) {
        return ReflectionHelpers.getField(window, "root");
    }

    private static IslandWindow.Turner turner(IslandWindow window) {
        return ReflectionHelpers.getField(window, "turner");
    }

    private static void layout(IslandWindow window, int width, int height) {
        View root = root(window);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    @Test public void theWindowHugsTheEdgeWithTheCamera() {
        IslandWindow window = window();
        WindowManager.LayoutParams params = ReflectionHelpers.getField(window, "params");
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(EXTENT, params.height);

        window.setSideways(IslandWindow.SIDE_LEFT);
        assertEquals(EXTENT, params.width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height);
        assertEquals(Gravity.LEFT, params.gravity & Gravity.HORIZONTAL_GRAVITY_MASK);

        window.setSideways(IslandWindow.SIDE_RIGHT);
        assertEquals(Gravity.RIGHT, params.gravity & Gravity.HORIZONTAL_GRAVITY_MASK);
        window.setWindowHeight(200);
        assertEquals("a taller island makes the window wider", 200, params.width);

        window.setSideways(IslandWindow.SIDE_NONE);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(200, params.height);
        assertEquals(0f, turner(window).getRotation(), 0f);
    }

    @Test public void theIslandsUprightCoordinatesLandOnTheWindowTurnedTowardsTheCamera() {
        IslandWindow window = window();
        window.setSideways(IslandWindow.SIDE_LEFT);
        layout(window, EXTENT, SCREEN_H);
        IslandWindow.Turner turner = turner(window);
        assertEquals("laid out as a portrait status bar", SCREEN_H, turner.getWidth());
        assertEquals(EXTENT, turner.getHeight());
        // camera on the left: the phone's top is the left edge, its left side the bottom
        float[] p = {10, 5};
        turner.getMatrix().mapPoints(p);
        assertEquals(5, p[0], 0.01);
        assertEquals(SCREEN_H - 10, p[1], 0.01);

        window.setSideways(IslandWindow.SIDE_RIGHT);
        layout(window, EXTENT, SCREEN_H);
        p = new float[] {10, 5};
        turner.getMatrix().mapPoints(p);
        assertEquals("camera on the right: the top is the right edge", EXTENT - 5, p[0], 0.01);
        assertEquals(10, p[1], 0.01);
    }

    @Test public void theTouchRegionIsTurnedWithTheIsland() {
        IslandWindow window = window();
        window.setSideways(IslandWindow.SIDE_LEFT);
        layout(window, EXTENT, SCREEN_H);
        Region region = new Region(100, 10, 180, 40); // a pill in upright coordinates
        ReflectionHelpers.callInstanceMethod(window, "toWindow", ReflectionHelpers.ClassParameter.from(Region.class, region));
        Rect bounds = region.getBounds();
        assertEquals(new Rect(10, SCREEN_H - 180, 40, SCREEN_H - 100), bounds);
        assertTrue(region.contains(25, SCREEN_H - 140));
        assertFalse("where the pill is upright is not where it is now", region.contains(140, 25));
    }

    @Test public void theCentreComesFromTheHoleOnTheEdge() {
        float[] left = IslandPlacementProbe.sidewaysCentre(IslandWindow.SIDE_LEFT, new Rect(20, 160, 50, 190), SCREEN_W, SCREEN_H);
        assertEquals("along the edge, from the bottom up", SCREEN_H - 175, left[0], 0.01);
        assertEquals("in from the left edge", 35, left[1], 0.01);
        float[] right = IslandPlacementProbe.sidewaysCentre(IslandWindow.SIDE_RIGHT, new Rect(750, 160, 780, 190), SCREEN_W, SCREEN_H);
        assertEquals(175, right[0], 0.01);
        assertEquals("in from the right edge", 35, right[1], 0.01);
    }

    @Test public void aRenderShowsThePillTurnedAtTheCamera() throws Exception {
        renderTurned("sideways-island", false, false);
    }

    @Test public void theFrostedStyleStillDrawsThePillWhileTurned() throws Exception {
        renderTurned("sideways-island-frosted", true, false);
    }

    @Test public void iMotionStillDrawsThePillWhileTurned() throws Exception {
        renderTurned("sideways-island-imotion", false, true);
    }

    private void renderTurned(String name, boolean frosted, boolean iMotion) throws Exception {
        GlyphIcon.setPlaybackFrozen(true);
        android.content.SharedPreferences prefs = ModuleSettings.openStore(RuntimeEnvironment.getApplication());
        prefs.edit().putInt(ModuleSettings.KEY_MATERIAL_STYLE, frosted ? ModuleSettings.MATERIAL_FROSTED : ModuleSettings.MATERIAL_CLASSIC)
                .putBoolean(ModuleSettings.KEY_I_MODE, iMotion).commit();
        try {
            IslandWindow window = window();
            window.view.setGeometry(SCREEN_H, SCREEN_H - 175, 35, 22, false);
            IslandItem item = new IslandItem("event:ringer");
            item.kind = IslandItem.Kind.EVENT;
            item.title = "Silent";
            item.icon = GlyphIcon.of(GlyphIcon.Kind.BELL_OFF);
            item.accent = 0xFFFF9F0A;
            item.trailing = IslandItem.Trailing.TEXT;
            item.trailingText = "Silent";
            window.view.setItems(List.of(item));
            window.setSideways(IslandWindow.SIDE_LEFT);
            layout(window, EXTENT, SCREEN_H);
            for (int i = 0; i < 60; i++) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
            }
            layout(window, EXTENT, SCREEN_H);
            Bitmap shot = Bitmap.createBitmap(EXTENT, SCREEN_H, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            canvas.drawColor(0xFF4A78C8);
            root(window).draw(canvas);
            RenderCapture.write(shot, new File("build/renders/" + name + ".png"));
            // the pill is a dark, upright-tall shape near the left edge around the hole's height
            int dark = 0;
            for (int y = 100; y < 260; y++) {
                for (int x = 15; x < 55; x++) {
                    int c = shot.getPixel(x, y);
                    if (Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40) dark++;
                }
            }
            assertTrue(name + ": a black pill along the left edge at the camera: " + dark, dark > 400);
        } finally {
            GlyphIcon.setPlaybackFrozen(false);
            prefs.edit().remove(ModuleSettings.KEY_MATERIAL_STYLE).remove(ModuleSettings.KEY_I_MODE).commit();
            ModuleSettings.configurePreview(prefs);
        }
    }
}
