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

    @Test public void openCardsOpenBesideTheCameraCentredOnItAndStayOnTheScreen() {
        float[] span = {60, 140, 12, 348}; // the cards in the island's own coordinates
        float[] left = IslandPlacementProbe.openCardsOffset(IslandWindow.SIDE_LEFT, 35, 175, 11, SCREEN_W, SCREEN_H, span, 8);
        assertEquals("the cards' left edge just past the camera", 35 + 11 + 8 - 12, left[0], 0.01);
        assertEquals("the cards' middle (100) moves to the camera (175)", 75, left[1], 0.01);
        float[] right = IslandPlacementProbe.openCardsOffset(IslandWindow.SIDE_RIGHT, 765, 175, 11, SCREEN_W, SCREEN_H, span, 8);
        assertEquals("the right edge just before a camera on the right", 765 - 11 - 8 - 348, right[0], 0.01);
        float[] low = IslandPlacementProbe.openCardsOffset(IslandWindow.SIDE_LEFT, 35, 340, 11, SCREEN_W, SCREEN_H, span, 8);
        assertEquals("kept above the bottom", SCREEN_H - 8 - 140, low[1], 0.01);
        float[] tall = IslandPlacementProbe.openCardsOffset(IslandWindow.SIDE_LEFT, 35, 175, 11, SCREEN_W, SCREEN_H,
                new float[] {60, 500, 12, 348}, 8);
        assertEquals("a stack taller than the screen starts at the top", 8 - 60, tall[1], 0.01);
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

    @Test public void openCardsAreDrawnUprightAndThePillTurnsBackAfterwards() {
        IslandWindow window = window();
        window.setSideways(IslandWindow.SIDE_LEFT);
        assertTrue(window.turned());
        window.setUprightWhileOpen(true);
        assertFalse(window.turned());
        WindowManager.LayoutParams params = ReflectionHelpers.getField(window, "params");
        assertEquals("across the top while cards are open", ViewGroup.LayoutParams.MATCH_PARENT, params.width);
        assertEquals(0f, turner(window).getRotation(), 0f);
        assertEquals("the pills stay at the camera, upright there", 90,
                (int) ReflectionHelpers.callInstanceMethod(window.view, "contentTurn"));
        window.setUprightWhileOpen(false);
        assertTrue(window.turned());
        assertEquals(-90f, turner(window).getRotation(), 0f);
        assertEquals("each element turns back the other way", 90, (int) ReflectionHelpers.callInstanceMethod(window.view, "contentTurn"));
        window.setSideways(IslandWindow.SIDE_NONE);
        window.setUprightWhileOpen(true);
        assertFalse("no effect without a side camera", window.isUprightWhileOpen());
    }

    private static IslandItem percentItem() {
        return percentItem("event:battery");
    }

    private static IslandItem percentItem(String key) {
        IslandItem item = new IslandItem(key);
        item.kind = IslandItem.Kind.EVENT;
        item.title = "Battery";
        item.icon = GlyphIcon.of(GlyphIcon.Kind.BELL_OFF);
        item.accent = 0xFF30D158;
        item.trailing = IslandItem.Trailing.TEXT;
        item.trailingText = "80%";
        return item;
    }

    /** The turned island with {@code items}, settled and drawn; the pixels of the window. */
    private Bitmap renderTurnedItems(String name, List<IslandItem> items) throws Exception {
        IslandWindow window = window();
        window.view.setGeometry(SCREEN_H, SCREEN_H - 175, 35, 22, false);
        window.setSideways(IslandWindow.SIDE_LEFT);
        window.view.setItems(items);
        if (items.size() > 1) {
            // the bubble splits off on a delayed post the detached test window never runs
            ReflectionHelpers.callInstanceMethod(ReflectionHelpers.getField(window.view, "director"), "startSplitNow");
        }
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
        return shot;
    }

    @Test public void theTextOfATurnedPillReadsUpright() throws Exception {
        GlyphIcon.setPlaybackFrozen(true);
        try {
            Bitmap shot = renderTurnedItems("sideways-upright-text", List.of(percentItem()));
            // the trailing end of the pill is towards the top of the screen (upright x runs up the left edge)
            int left = Integer.MAX_VALUE, right = -1, top = Integer.MAX_VALUE, bottom = -1;
            for (int y = 60; y < 175; y++) {
                for (int x = 0; x < 80; x++) {
                    int c = shot.getPixel(x, y);
                    if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200) {
                        left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
                    }
                }
            }
            assertTrue("the text is drawn", right > left);
            int width = right - left + 1, height = bottom - top + 1;
            assertTrue("\"80%\" stands upright: wider than tall on the screen (" + width + "x" + height + ")", width > height);
        } finally {
            GlyphIcon.setPlaybackFrozen(false);
        }
    }

    @Test public void wordsTooLongToFitAcrossATurnedPillAreLeftOutAndThePillIsShorter() {
        IslandWindow window = window();
        window.view.setGeometry(SCREEN_H, SCREEN_H - 175, 35, 0, false);
        IslandItem item = percentItem();
        item.trailingText = "Fingerprint";
        float flat = dev.android.dynamicisland.island.view.CompactWidthProbe.width(window.view, item);
        window.setSideways(IslandWindow.SIDE_LEFT);
        float turned = dev.android.dynamicisland.island.view.CompactWidthProbe.width(window.view, item);
        assertTrue("shorter: " + turned + " < " + flat, turned < flat);
        item.trailingText = "80%";
        float percent = dev.android.dynamicisland.island.view.CompactWidthProbe.width(window.view, item);
        assertTrue("a short value keeps a slot", percent > turned);
    }

    @Test public void aRenderOfTheTurnedPillWithASecondActivity() throws Exception {
        GlyphIcon.setPlaybackFrozen(true);
        try {
            IslandItem second = percentItem("event:second");
            second.icon = GlyphIcon.of(GlyphIcon.Kind.TORCH);
            second.trailing = IslandItem.Trailing.NONE;
            Bitmap shot = renderTurnedItems("sideways-two-activities", List.of(percentItem(), second));
            int dark = 0;
            for (int y = 0; y < SCREEN_H; y++) for (int x = 0; x < EXTENT; x++) {
                int c = shot.getPixel(x, y);
                if (Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40) dark++;
            }
            assertTrue(dark > 400);
        } finally {
            GlyphIcon.setPlaybackFrozen(false);
        }
    }

    @Test public void aRenderOfAnOpenCardWhileTheIslandSitsAtASideCamera() throws Exception {
        GlyphIcon.setPlaybackFrozen(true);
        try {
            IslandWindow window = window();
            window.view.setGeometry(SCREEN_H, SCREEN_H - 175, 35, 22, false);
            window.setSideways(IslandWindow.SIDE_LEFT);
            IslandItem item = percentItem();
            item.subtitle = "Charging";
            window.view.setItems(List.of(item));
            window.view.peek(item, 5000);
            window.setUprightWhileOpen(true); // what the placement does when the island reports it opened
            float[] span = new float[4];
            for (int i = 0; i < 60; i++) {
                layout(window, SCREEN_W, SCREEN_H);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
                window.view.doFrame(android.os.SystemClock.uptimeMillis() * 1_000_000L);
                if (window.view.openCardsSpan(span)) { // what the placement does every frame
                    float[] offset = IslandPlacementProbe.openCardsOffset(IslandWindow.SIDE_LEFT, 35, 175, 11, SCREEN_W, SCREEN_H,
                            span, 8);
                    window.setOpenOffset(offset[0], offset[1]);
                }
            }
            layout(window, SCREEN_W, SCREEN_H);
            assertTrue(window.view.isExpanded());
            Bitmap shot = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            canvas.drawColor(0xFF4A78C8);
            root(window).draw(canvas);
            RenderCapture.write(shot, new File("build/renders/sideways-open-card.png"));
            int left = Integer.MAX_VALUE, right = -1, top = Integer.MAX_VALUE, bottom = -1;
            for (int y = 0; y < SCREEN_H; y++) for (int x = 46; x < SCREEN_W; x++) { // the card, right of the camera dot
                int c = shot.getPixel(x, y);
                if (Color.red(c) < 60 && Color.green(c) < 60 && Color.blue(c) < 60) {
                    left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
            }
            assertTrue("an upright card: wider than tall (" + (right - left) + "x" + (bottom - top) + ")", right - left > 2 * (bottom - top));
            assertEquals("centred on the camera's height", 175, (top + bottom) / 2f, 12);
            assertTrue("right beside the camera, not under it: " + left, left >= 35 + 11 && left < 70);
            // the pill stays where the turned island has it: at the camera on the left edge
            int pill = 0;
            for (int y = 160; y < 190; y++) for (int x = 15; x < 46; x++) { // around the camera (35, 175)
                int c = shot.getPixel(x, y);
                if (Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40) pill++;
            }
            assertTrue("the pill at the camera: " + pill, pill > 150);
            assertFalse("no turned window while it is open", window.turned());
        } finally {
            GlyphIcon.setPlaybackFrozen(false);
        }
    }

    @Test public void aRenderOfIMotionWithTwoActivitiesOpenAtASideCamera() throws Exception {
        GlyphIcon.setPlaybackFrozen(true);
        android.content.SharedPreferences prefs = ModuleSettings.openStore(RuntimeEnvironment.getApplication());
        prefs.edit().putBoolean(ModuleSettings.KEY_I_MODE, true).commit();
        try {
            IslandWindow window = window();
            window.view.setGeometry(SCREEN_H, SCREEN_H - 175, 35, 22, false);
            window.setSideways(IslandWindow.SIDE_LEFT);
            IslandItem timer = percentItem("timer");
            timer.kind = IslandItem.Kind.LIVE;
            timer.title = "0:20.05";
            timer.subtitle = "Stopwatch";
            IslandItem recording = percentItem("recording");
            recording.icon = GlyphIcon.of(GlyphIcon.Kind.MIC);
            recording.trailingText = "0:06";
            window.view.setItems(List.of(timer, recording));
            for (int i = 0; i < 30; i++) {
                layout(window, EXTENT, SCREEN_H);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
                window.view.doFrame(android.os.SystemClock.uptimeMillis() * 1_000_000L);
            }
            ReflectionHelpers.callInstanceMethod(ReflectionHelpers.getField(window.view, "director"), "expand",
                    ReflectionHelpers.ClassParameter.from(List.class, List.of(timer)),
                    ReflectionHelpers.ClassParameter.from(long.class, 0L));
            window.setUprightWhileOpen(true);
            float[] span = new float[4];
            for (int i = 0; i < 60; i++) {
                layout(window, SCREEN_W, SCREEN_H);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16));
                window.view.doFrame(android.os.SystemClock.uptimeMillis() * 1_000_000L);
                if (window.view.openCardsSpan(span)) {
                    float[] offset = IslandPlacementProbe.openCardsOffset(IslandWindow.SIDE_LEFT, 35, 175, 11, SCREEN_W, SCREEN_H,
                            span, 8);
                    window.setOpenOffset(offset[0], offset[1]);
                }
            }
            layout(window, SCREEN_W, SCREEN_H);
            Bitmap shot = Bitmap.createBitmap(SCREEN_W, SCREEN_H, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(shot);
            canvas.drawColor(0xFF4A78C8);
            root(window).draw(canvas);
            RenderCapture.write(shot, new File("build/renders/sideways-imotion-open.png"));
        } finally {
            GlyphIcon.setPlaybackFrozen(false);
            prefs.edit().remove(ModuleSettings.KEY_I_MODE).commit();
            ModuleSettings.configurePreview(prefs);
        }
    }
}
