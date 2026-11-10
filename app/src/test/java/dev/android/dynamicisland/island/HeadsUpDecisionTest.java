package dev.android.dynamicisland.island;

import static org.junit.Assert.*;
import android.app.Notification;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.feedback.AudioLevels;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.island.window.IslandWindow;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.hooks.HeadsUpHooks;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, qualifiers="w360dp-h800dp-mdpi")
public class HeadsUpDecisionTest {
    Context app;
    IslandSources sources;
    IslandPlacement placement;
    HeadsUpTakeover takeover;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        Handler main = new Handler(Looper.getMainLooper());
        ReflectionHelpers.setStaticField(ModuleSettings.class, "headsUpIsland", true);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "foregroundNotifications", true);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "allNotifications", true);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "otpCard", false);
        sources = new IslandSources(app, getClass().getClassLoader(), main, () -> {});
        placement = new IslandPlacement(app, main, getClass().getClassLoader(), IslandHost.NONE,
                new IslandWindow.Listener() {
                    public void onWindowConfigurationChanged() {}
                    public void onWindowInsetsChanged() {}
                }, new AudioLevels());
        placement.ensureWindow(true);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("window must attach before takeover: " + dev.android.dynamicisland.xposed.Diagnostics.report(), placement.canPresent());
        takeover = new HeadsUpTakeover(sources, placement, () -> {
            var item = sources.peeks.showing();
            placement.view().setItems(item == null ? List.of() : List.of(item));
        });
    }
    @After public void reset() {
        ReflectionHelpers.setStaticField(ModuleSettings.class, "allNotifications", false);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "foregroundNotifications", false);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "otpCard", true);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "headsUpIsland", true);
        ReflectionHelpers.setStaticField(ModuleSettings.class, "alwaysVisible", false);
        ReflectionHelpers.setStaticField(IslandController.class, "instance", null);
        ReflectionHelpers.setStaticField(HeadsUpHooks.class, "manager", null);
    }
    StatusBarNotification note(int id, long time) {
        Notification n = new Notification.Builder(app, "messages").setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("Test").setContentText("Message").build();
        return new StatusBarNotification(app.getPackageName(), app.getPackageName(), id, null,
                10100, 1, 0, n, android.os.Process.myUserHandle(), time);
    }
    static class Return implements HeadsUpHooks.NativeRestore {
        boolean valid = true;
        int restored;
        public boolean valid() { return valid; }
        public boolean restore() { restored++; return true; }
    }
    @Test public void nativeFallbackNeverQueuesOrShowsLaterIncludingAllNotifications() {
        var one = note(1, 100); var two = note(2, System.currentTimeMillis());
        takeover.setSnapshot(List.of(one, two));
        var decision=takeover.decide(one,null);
        assertEquals(dev.android.dynamicisland.xposed.Diagnostics.report(),HeadsUpHooks.Decision.PRESENTED,decision);
        assertEquals(HeadsUpHooks.Decision.NATIVE, takeover.decide(two, null));
        sources.peeks.dismiss(sources.peeks.showing());
        takeover.reconcile(); takeover.scanAll(List.of(two));
        assertNull(sources.peeks.showing());
    }
    @Test public void reservedAlertsPromoteOnceAndUpdatesReplacePendingVersion() {
        var one=note(1,100); var two=note(2,101); var update=note(2,101);
        assertTrue(takeover.take(one));
        Return original = new Return(), newer = new Return();
        assertEquals(HeadsUpHooks.Decision.RESERVED,takeover.decide(two,original));
        assertEquals(HeadsUpHooks.Decision.RESERVED,takeover.decide(two,original));
        assertEquals(HeadsUpHooks.Decision.RESERVED,takeover.decide(update,newer));
        sources.peeks.dismiss(sources.peeks.showing()); takeover.reconcile();
        assertEquals(update.getKey(),sources.peeks.showing().sourceKey);
        assertTrue(takeover.owns(update.getKey()));
        assertEquals(0,original.restored); assertEquals(0,newer.restored);
    }
    @Test public void fullQueueFallsBackAndRemovedReservationsNeverReplay() {
        assertTrue(takeover.take(note(1,100)));
        java.util.ArrayList<StatusBarNotification> all=new java.util.ArrayList<>();
        Return path=new Return();
        for(int id=2;id<=5;id++) {
            var event=note(id,100+id);all.add(event);
            assertEquals(HeadsUpHooks.Decision.RESERVED,takeover.decide(event,path));
        }
        assertEquals(HeadsUpHooks.Decision.NATIVE,takeover.decide(note(6,106),path));
        takeover.setSnapshot(all);takeover.setSnapshot(List.of());
        sources.peeks.dismiss(sources.peeks.showing());takeover.reconcile();
        assertNull(sources.peeks.showing());assertEquals(0,path.restored);
    }
    @Test public void expiryAndDisabledSettingsReturnOnlyValidReservations() {
        assertTrue(takeover.take(note(1,100)));
        Return path=new Return();
        assertEquals(HeadsUpHooks.Decision.RESERVED,takeover.decide(note(2,101),path));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));takeover.reconcile();
        assertEquals(1,path.restored);
        assertTrue(takeover.take(note(3,102)));
        Return disabled=new Return();
        assertEquals(HeadsUpHooks.Decision.RESERVED,takeover.decide(note(4,103),disabled));
        ReflectionHelpers.setStaticField(ModuleSettings.class,"headsUpIsland",false);
        ReflectionHelpers.setStaticField(ModuleSettings.class,"allNotifications",false);
        takeover.reconcile();assertEquals(1,disabled.restored);
    }
    @Test public void suppressionReturnsPendingAndDropsOwnershipEvenInRecovery() {
        assertTrue(takeover.take(note(1,100)));
        Return path=new Return();
        assertEquals(HeadsUpHooks.Decision.RESERVED,takeover.decide(note(2,101),path));
        placement.view().setSuppressed(true);
        takeover.reconcile();assertEquals(1,path.restored);
        assertFalse(takeover.owns(note(1,100).getKey()));
    }
    @Test public void sameTimestampUpdateRefreshesActiveContentAndOldPostingCannotOwnIt() {
        var old=note(1,100);var update=note(1,100);
        assertTrue(takeover.take(old));
        var first=sources.peeks.showing();
        takeover.setSnapshot(List.of(update));
        assertNotSame(first,sources.peeks.showing());
        assertTrue(takeover.owns(update.getKey()));
    }
    @Test public void offMainThreadLeavesNativeWithoutWaitingOrLaterCard() throws Exception {
        var event=note(1,System.currentTimeMillis());
        java.util.concurrent.atomic.AtomicReference<HeadsUpHooks.Decision> result=new java.util.concurrent.atomic.AtomicReference<>();
        Thread thread=new Thread(() -> result.set(takeover.decide(event,null)));
        thread.start();thread.join();assertEquals(HeadsUpHooks.Decision.NATIVE,result.get());
        takeover.scanAll(List.of(event));assertNull(sources.peeks.showing());
    }
    @Test public void codeSettingRemainsIndependentAndForegroundFilterStillAppliesInRecovery() {
        ReflectionHelpers.setStaticField(ModuleSettings.class,"headsUpIsland",false);
        ReflectionHelpers.setStaticField(ModuleSettings.class,"allNotifications",false);
        ReflectionHelpers.setStaticField(ModuleSettings.class,"otpCard",true);
        var code=note(1,100);
        code.getNotification().extras.putCharSequence(Notification.EXTRA_TEXT,"Your verification code is 123456");
        assertTrue(takeover.take(code));assertNotNull(sources.peeks.showing().otpCode);
        var updatedCode=note(1,101);
        updatedCode.getNotification().extras.putCharSequence(Notification.EXTRA_TEXT,"Your verification code is 123456");
        assertEquals(HeadsUpHooks.Decision.PRESENTED,takeover.decide(updatedCode,null));
        sources.peeks.dismiss(sources.peeks.showing());
        ReflectionHelpers.setStaticField(ModuleSettings.class,"headsUpIsland",true);
        ReflectionHelpers.setStaticField(ModuleSettings.class,"foregroundNotifications",false);
        ReflectionHelpers.setStaticField(ModuleSettings.class,"experimentalRecovery",true);
        com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper.baseActivityComponentName=
                new android.content.ComponentName(app.getPackageName(),"Main");
        try { assertFalse(takeover.take(note(2,101))); }
        finally {
            com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper.baseActivityComponentName=null;
            ReflectionHelpers.setStaticField(ModuleSettings.class,"experimentalRecovery",false);
        }
    }
    public static class StockManager {
        int removals;
        public boolean removeNotification(String key, boolean immediate) { removals++;return true; }
        public Object getHeadsUpEntry(String key) { return this; }
    }
    @Test public void retriesAreBoundedAndNeverRemoveAnotherPostingOfTheSameKey() {
        IslandController controller=ReflectionHelpers.callConstructor(IslandController.class,
                ReflectionHelpers.ClassParameter.from(Context.class,app),
                ReflectionHelpers.ClassParameter.from(ClassLoader.class,getClass().getClassLoader()));
        ReflectionHelpers.setField(controller,"headsUp",takeover);
        ReflectionHelpers.setStaticField(IslandController.class,"instance",controller);
        StockManager manager=new StockManager();
        ReflectionHelpers.setStaticField(HeadsUpHooks.class,"manager",manager);
        assertTrue(takeover.take(note(1,100)));
        HeadsUpHooks.dismissStock(note(1,100).getKey());assertEquals(1,manager.removals);
        assertTrue(takeover.take(note(1,101)));
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertEquals(3,manager.removals); // initial recovery and first retry belong only to the new card
        String key=note(1,101).getKey();
        for(int i=0;i<8;i++) HeadsUpHooks.dismissStock(key);
        assertEquals(4,manager.removals); // one old attempt and at most three for the new posting
    }
    @Test public void lockedDeviceKeepsNativeEvenBeforeSuppressionCallback() {
        android.app.KeyguardManager keyguard=app.getSystemService(android.app.KeyguardManager.class);
        org.robolectric.Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        assertFalse(takeover.take(note(1,100)));
    }
    @Test public void departingCardsAndDetachedWindowsNeverAuthorizeRemoval() {
        var event=note(1,100);assertTrue(takeover.take(event));
        Object scene=ReflectionHelpers.getField(placement.view(),"scene");
        java.util.List<?> cards=ReflectionHelpers.getField(scene,"cards");
        for(Object card:cards) ReflectionHelpers.setField(card,"leaving",true);
        assertFalse(takeover.owns(event.getKey()));
        ReflectionHelpers.callInstanceMethod(placement.window(),"detach");
        assertEquals(HeadsUpHooks.Decision.NATIVE,takeover.decide(note(2,101),new Return()));
    }
    @Test public void alwaysVisibleIdleIsValidButCriticalAndForeignEventsStayNative() {
        ReflectionHelpers.setStaticField(ModuleSettings.class,"alwaysVisible",true);
        assertTrue(takeover.take(note(1,100)));
        var call=note(2,101);call.getNotification().category=Notification.CATEGORY_CALL;
        assertFalse(takeover.take(call));
        var alarm=note(3,102);alarm.getNotification().category=Notification.CATEGORY_ALARM;
        assertFalse(takeover.take(alarm));
        var event=note(4,103);
        var foreign=new StatusBarNotification(app.getPackageName(),app.getPackageName(),4,null,10100,1,0,
                event.getNotification(),android.os.UserHandle.getUserHandleForUid(1010100),103);
        assertFalse(takeover.take(foreign));
    }
}
