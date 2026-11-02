package dev.android.dynamicisland.island.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

/** The default presentation (the stack of cards): a swipe down on the small pill opens its cards. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class SwipeDownOpensTest {
    private final List<String> shadePulls = new ArrayList<>();

    private IslandView view(Context context) {
        IslandHost host = (IslandHost) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IslandHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onShadeDragStart" -> { shadePulls.add("start"); yield null; }
                    case "hasMinimized", "onShadeFade" -> false;
                    default -> null;
                });
        IslandView view = new IslandView(context, host);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(view);
        view.setGeometry(440, 220, 30, 22, false);
        view.layout(0, 0, 440, 400);
        IslandItem item = new IslandItem("timer");
        item.kind = IslandItem.Kind.TIMER;
        item.title = "Stopwatch";
        item.icon = GlyphIcon.of(GlyphIcon.Kind.BELL);
        item.trailing = IslandItem.Trailing.TEXT;
        item.trailingText = "00:55";
        List<IslandItem> items = new ArrayList<>();
        items.add(item);
        view.setItems(items);
        step(view, 80);
        return view;
    }

    private static void step(IslandView view, int frames) {
        for (int i = 0; i < frames; i++) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16));
            view.doFrame(SystemClock.uptimeMillis() * 1_000_000L);
            Choreographer.getInstance().removeFrameCallback(view);
        }
    }

    private static void touch(IslandView view, int action, long down, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    private void pullDownOnThePill(IslandView view) {
        Shapes shapes = ReflectionHelpers.getField(view, "shapes");
        RectF pill = new RectF();
        shapes.paintedMain(pill);
        long down = SystemClock.uptimeMillis();
        touch(view, MotionEvent.ACTION_DOWN, down, pill.centerX(), pill.centerY());
        touch(view, MotionEvent.ACTION_MOVE, down, pill.centerX(), pill.centerY() + 24);
        touch(view, MotionEvent.ACTION_MOVE, down, pill.centerX(), pill.centerY() + 60);
        touch(view, MotionEvent.ACTION_UP, down, pill.centerX(), pill.centerY() + 120);
    }

    @Test public void aPullDownOnThePillOpensTheCardsByDefault() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("swipe-down-stack", 0);
        try {
            prefs.edit().clear().putBoolean(ModuleSettings.KEY_AMBIENT_GLOW, false).commit();
            ModuleSettings.configurePreview(prefs);
            IslandView view = view(context);
            Scene scene = ReflectionHelpers.getField(view, "scene");
            assertEquals(Mode.COMPACT, scene.mode);
            pullDownOnThePill(view);
            step(view, 80);
            assertEquals("nothing went to the shade", 0, shadePulls.size());
            assertEquals("the cards opened", Mode.EXPANDED, scene.mode);
            assertFalse(scene.cards.isEmpty());
        } finally {
            prefs.edit().clear().commit();
            ModuleSettings.configurePreview(prefs);
        }
    }

    @Test public void withTheOptionOffAPullDownOnThePillGoesToTheShade() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("swipe-down-stack-off", 0);
        try {
            prefs.edit().clear().putBoolean(ModuleSettings.KEY_AMBIENT_GLOW, false)
                    .putBoolean(ModuleSettings.KEY_SWIPE_DOWN_OPENS, false).commit();
            ModuleSettings.configurePreview(prefs);
            IslandView view = view(context);
            Scene scene = ReflectionHelpers.getField(view, "scene");
            pullDownOnThePill(view);
            step(view, 60);
            assertEquals("the pull went to the shade", 1, shadePulls.size());
            assertEquals(Mode.COMPACT, scene.mode);
            assertTrue(scene.cards.isEmpty());
        } finally {
            prefs.edit().clear().commit();
            ModuleSettings.configurePreview(prefs);
        }
    }
}
