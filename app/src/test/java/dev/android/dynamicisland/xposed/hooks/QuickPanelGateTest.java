package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper;
import dev.android.dynamicisland.island.IslandController;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.settings.ModuleSettings;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

/** Exercise the system quick-panel signal, not the island's expanded activity list. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class QuickPanelGateTest {
    public static class Data {
        public String mNotiID = "quick-panel-probe", mPackageName = "example.sports", mPrimaryInfo = "Sports";
        public boolean mIsMediaOngoingData;
        public Object mNotificationEntry, mPendingIntent;
    }
    public static class Entry { public String getKey() { return "quick-panel-probe"; } }
    public static class Content extends FrameLayout {
        public View mContractedChild, mExpandedChild, mHeadsUpChild;
        public boolean mExpandable = true;
        public int mSmallHeight = 80, mMinContractedHeight = 80, mHeadsUpHeight = 80;
        Content(Context context) { super(context); mContractedChild = new View(context); addView(mContractedChild); }
        public void setContractedChild(View next) {
            if (mContractedChild != null) removeView(mContractedChild);
            mContractedChild = next;
            if (next != null) addView(next);
        }
        public void setExpandedChild(View next) { mExpandedChild = next; }
        public void setHeadsUpChild(View next) { mHeadsUpChild = next; }
        public void updateExpandButtons(boolean value) { mExpandable = value; }
        public void selectLayout(boolean animate, boolean force) { }
    }
    public static class ProbeExpandableNotificationRow extends FrameLayout {
        public final Content mPrivateLayout;
        public final Entry mEntryAdapter = new Entry();
        public boolean mExpandable = true;
        int heightChanges;
        ProbeExpandableNotificationRow(Context context) {
            super(context); mPrivateLayout = new Content(context); addView(mPrivateLayout);
        }
        public void notifyHeightChanged(String reason, boolean animate) { heightChanges++; }
        public void initBackground() { }
        public void updateBackground() { }
    }

    @Test public void compactPillFadesWithoutRebindingRowsAndRealCardHandoffsStillWork() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Object previous = ReflectionHelpers.getStaticField(IslandController.class, "instance");
        var prefs = context.getSharedPreferences("quick-panel-gate", 0);
        prefs.edit().clear().commit(); ModuleSettings.configurePreview(prefs);
        var store = OngoingActivityDataHelper.mOngoingActivityLists;
        store.clear(); store.add(new Data());
        IslandView overlay = null;
        ProbeExpandableNotificationRow row = new ProbeExpandableNotificationRow(context);
        row.layout(0, 0, 360, 180); row.mPrivateLayout.layout(0, 0, 360, 180);
        View original = row.mPrivateLayout.mContractedChild;
        try {
            IslandController controller = ReflectionHelpers.callConstructor(IslandController.class,
                    from(Context.class, context), from(ClassLoader.class, getClass().getClassLoader()));
            ReflectionHelpers.setStaticField(IslandController.class, "instance", controller);
            int[] frameNotifications = {0};
            IslandHost host = (IslandHost) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{IslandHost.class}, (proxy, method, args) -> {
                        if (method.getName().equals("onShadeFade")) return true;
                        if (method.getName().equals("hasMinimized")) return false;
                        if (method.getName().equals("onIslandFrame")) { frameNotifications[0]++; controller.onIslandFrame(); }
                        return null;
                    });
            overlay = new IslandView(context, host);
            ReflectionHelpers.setField(ReflectionHelpers.getField(controller, "placement"), "view", overlay);
            overlay.setGeometry(360, 180, 30, 18, false); overlay.layout(0, 0, 360, 1000);
            var item = ShadeLiveCards.item("quick-panel-probe"); assertNotNull(item);
            overlay.setItems(List.of(item)); step(overlay, 120);
            var bind = ShadeLiveCards.class.getDeclaredMethod("bind", Object.class); bind.setAccessible(true); bind.invoke(null, row);
            View card = row.mPrivateLayout.mContractedChild;
            assertTrue(card instanceof LiveCardView);
            int heightChanges = row.heightChanges;
            for (int cycle = 0; cycle < 50; cycle++) {
                assertTrue(overlay.visibleAlpha() > .99f);
                controller.setShadeExpansion(.1f); step(overlay, 1);
                assertTrue("the pill shrinks gradually", overlay.visibleAlpha() > 0);
                step(overlay, 119); assertTrue(overlay.visibleAlpha() < .01f);
                int notifications = frameNotifications[0];
                controller.setShadeExpansion(.5f); controller.setShadeExpansion(1f);
                assertFalse("hidden pill does not queue frames for panel dragging",
                        (boolean) ReflectionHelpers.getField(overlay, "frameScheduled"));
                assertEquals(notifications, frameNotifications[0]);
                controller.setShadeExpansion(0); step(overlay, 150);
                assertTrue(overlay.visibleAlpha() > .99f);
                assertSame("system panel gestures retain the notification card", card, row.mPrivateLayout.mContractedChild);
            }
            assertEquals("no notification height changes from 50 compact-panel cycles", heightChanges, row.heightChanges);
            // Removing the broad shade-toggle refresh must not break real overlay-card handoffs.
            overlay.peek(item, 60000); assertTrue(overlay.isExpanded());
            assertSame("original shade row while the overlay owns the card", original, row.mPrivateLayout.mContractedChild);
            controller.setShadeExpansion(1); step(overlay, 150);
            assertTrue("shade copy returns after the overlay content fades",
                    row.mPrivateLayout.mContractedChild instanceof LiveCardView);
            CardRowState.installedIn(row.mPrivateLayout).restore();
            assertSame(original, row.mPrivateLayout.mContractedChild);
        } finally {
            if (overlay != null) {
                android.view.Choreographer.getInstance().removeFrameCallback(overlay);
                overlay.setItems(List.of());
                android.view.Choreographer.getInstance().removeFrameCallback(overlay);
            }
            CardRowState state = CardRowState.installedIn(row.mPrivateLayout);
            if (state != null) state.restore();
            ReflectionHelpers.setStaticField(IslandController.class, "instance", previous);
            store.clear(); prefs.edit().clear().commit(); ModuleSettings.configurePreview(prefs);
        }
    }

    private static void step(IslandView view, int frames) {
        for (int i = 0; i < frames; i++) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(16));
            view.doFrame(SystemClock.uptimeMillis() * 1_000_000L);
        }
    }
}
