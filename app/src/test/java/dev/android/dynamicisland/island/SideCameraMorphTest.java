package dev.android.dynamicisland.island;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import dev.android.dynamicisland.island.feedback.AudioLevels;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.island.window.IslandWindow;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.testutil.RenderCapture;
import java.io.File;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;

/** iMotion at a side camera: the card morphs out of the pill where it is drawn, as in portrait, and back into it. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w800dp-h360dp-land-mdpi")
public class SideCameraMorphTest {
    private final IslandPlacement[] placement = new IslandPlacement[1];

    /** Forwards the island's expand reports to the placement, as the controller does. */
    private final IslandHost host = new IslandHost() {
        @Override public boolean hasMinimized() { return false; }
        @Override public void onCycle(int direction) { }
        @Override public void onExpandedChanged(boolean expanded) { placement[0].onExpandedChanged(expanded); }
        @Override public void onIslandFrame() { }
        @Override public void onItemAction(IslandItem item, IslandItem.Action action) { }
        @Override public void onMinimize(IslandItem item) { }
        @Override public void onOpenItem(IslandItem item, RectF from) { }
        @Override public void onRestore() { }
        @Override public void onShadeDragEvent(MotionEvent event) { }
        @Override public void onShadeDragStart(float downX, float downY, long downTime) { }
        @Override public boolean onShadeFade(float alpha, float scale, float pivotX, float pivotY) { return false; }
        @Override public void onWindowHeightNeeded(int heightPx) { }
        @Override public AudioLevels audio() { return levels; }
    };
    private final AudioLevels levels = new AudioLevels();

    private static void frames(IslandWindow window, int count, int width, int height) {
        View root = ReflectionHelpers.getField(window, "root");
        for (int i = 0; i < count; i++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16));
            window.view.doFrame(android.os.SystemClock.uptimeMillis() * 1_000_000L);
        }
    }

    private static float spring(Object card, String name) {
        return (float) ReflectionHelpers.callInstanceMethod(ReflectionHelpers.getField(card, name), "value");
    }

    private static Bitmap shot(IslandWindow window, int width, int height) {
        View root = ReflectionHelpers.getField(window, "root");
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF4A78C8);
        root.draw(c);
        return b;
    }

    @Test public void theCardGrowsOutOfThePillAtTheCameraAndFoldsBackIntoIt() throws Exception {
        GlyphIcon.setPlaybackFrozen(true);
        android.content.SharedPreferences prefs = ModuleSettings.openStore(RuntimeEnvironment.getApplication());
        prefs.edit().putBoolean(ModuleSettings.KEY_I_MODE, true).commit();
        ModuleSettings.configurePreview(prefs);
        try {
            placement[0] = new IslandPlacement(RuntimeEnvironment.getApplication(), new Handler(Looper.getMainLooper()),
                    getClass().getClassLoader(), host, new IslandWindow.Listener() {
                        @Override public void onWindowConfigurationChanged() { }
                        @Override public void onWindowInsetsChanged() { }
                    }, new AudioLevels());
            placement[0].ensureWindow(true);
            IslandWindow window = placement[0].window();
            window.view.setSuppressed(false);
            window.view.setGeometry(360, 185, 35, 22, false);
            window.setSideways(IslandWindow.SIDE_LEFT);
            ReflectionHelpers.setField(placement[0], "sideHoleX", 35f);
            ReflectionHelpers.setField(placement[0], "sideHoleY", 175f);
            ReflectionHelpers.setField(placement[0], "sideHoleR", 11f);
            ReflectionHelpers.setField(placement[0], "sideScreenW", 800);
            ReflectionHelpers.setField(placement[0], "sideScreenH", 360);
            IslandItem timer = new IslandItem("timer");
            timer.kind = IslandItem.Kind.LIVE;
            timer.title = "0:21.07";
            timer.subtitle = "Stopwatch";
            timer.icon = GlyphIcon.of(GlyphIcon.Kind.BELL);
            timer.trailing = IslandItem.Trailing.TEXT;
            timer.trailingText = "0:21";
            window.view.setItems(List.of(timer));
            frames(window, 30, 120, 360);

            ReflectionHelpers.callInstanceMethod(ReflectionHelpers.getField(window.view, "director"), "expand",
                    ReflectionHelpers.ClassParameter.from(List.class, List.of(timer)),
                    ReflectionHelpers.ClassParameter.from(long.class, 0L));
            assertTrue(window.isUprightWhileOpen());
            View turner = ReflectionHelpers.getField(window, "turner");
            assertTrue("still turned while the window is the narrow one at the edge", turner.getRotation() == -90f);
            View root = ReflectionHelpers.getField(window, "root");
            root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 800, 360); // the window has its new shape: the island turns upright now
            assertTrue("upright once it covers the screen", turner.getRotation() == 0f);
            float[] offset = {ReflectionHelpers.getField(placement[0], "openOffsetX"), ReflectionHelpers.getField(placement[0], "openOffsetY")};
            Object card = ((List<?>) ReflectionHelpers.getField(ReflectionHelpers.getField(window.view, "scene"), "cards")).get(0);
            // where the morph starts, on the screen: the pill at the camera, standing (taller than wide)
            float cx = spring(card, "cx") + offset[0], cy = spring(card, "cy") + offset[1];
            float w = spring(card, "w"), h = spring(card, "h");
            assertTrue("starts as the standing pill: " + w + "x" + h, h > w * 1.5f);
            assertTrue("starts at the camera: " + cx + "," + cy, Math.abs(cx - 35) < 12 && Math.abs(cy - 175) < 20);
            float targetW = ReflectionHelpers.getField(card, "targetW");
            assertTrue("and becomes the wide card", targetW > 200);
            frames(window, 60, 800, 360);

            window.view.collapse();
            float endW = ReflectionHelpers.getField(card, "targetW"), endH = ReflectionHelpers.getField(card, "targetH");
            float endCy = (float) ReflectionHelpers.getField(card, "targetCy") + offset[1];
            assertTrue("folds back into the standing pill: " + endW + "x" + endH, endH > endW * 1.5f);
            assertTrue("at the camera: " + endCy, Math.abs(endCy - 175) < 20);
            frames(window, 80, 800, 360);
            assertTrue("then the pill turns back at the camera", window.turned());
            RenderCapture.write(shot(window, 120, 360), new File("build/renders/sideways-imotion-after-close.png"));
        } finally {
            GlyphIcon.setPlaybackFrozen(false);
            prefs.edit().remove(ModuleSettings.KEY_I_MODE).commit();
            ModuleSettings.configurePreview(prefs);
        }
    }

}
