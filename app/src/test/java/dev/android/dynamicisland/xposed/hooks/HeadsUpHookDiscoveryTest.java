package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.*;
import android.service.notification.StatusBarNotification;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class HeadsUpHookDiscoveryTest {
    public enum Pin { NotPinned, PinnedBySystem, PinnedByUser }
    public static class Legacy {
        public boolean shouldHeadsUpBecomePinned(Object entry) { return true; }
        public void setEntryPinned(Object entry, boolean pinned) {}
        public void updateNotificationInternal(Object entry, boolean alert) {}
        public void setEntryPinned(Object entry, int unknown) {}
        public String shouldHeadsUpBecomePinned(String unknown) { return "unknown"; }
    }
    public static class Modern extends Legacy {
        public void setEntryPinned(Object entry, Pin pinned, String reason) {}
        public void setEntryPinned(Object entry, Pin pinned, int unknown) {}
    }
    @Test public void walksAllHierarchiesAndDeduplicatesSharedDeclarations() {
        var methods=HeadsUpHooks.declarations(null, Legacy.class, Modern.class);
        assertEquals(4,methods.size());
        assertEquals(2,methods.stream().filter(m->m.getName().equals("setEntryPinned")).count());
        assertTrue(HeadsUpHooks.declarations((Class<?>)null).isEmpty());
    }
    @Test public void booleanAndActualStatusConstantsPreserveUnknownAndUserRequests() {
        assertEquals(false,HeadsUpHooks.unpinned(true));
        assertNull(HeadsUpHooks.unpinned(false));
        assertEquals(Pin.NotPinned,HeadsUpHooks.unpinned(Pin.PinnedBySystem));
        assertNull(HeadsUpHooks.unpinned(Pin.PinnedByUser));
        assertNull(HeadsUpHooks.unpinned("PinnedBySystem"));
        assertNull(HeadsUpHooks.unpinned(null));
    }
    public static class NotificationEntry {
        public StatusBarNotification mSbn;
    }
    public static class HeadsUpEntry {
        public NotificationEntry mEntry = new NotificationEntry();
    }
    public static class Restorable extends Legacy {
        public HeadsUpEntry current = new HeadsUpEntry();
        boolean permitted = true, pinned;
        public Object getHeadsUpEntry(String key) { return current; }
        @Override public boolean shouldHeadsUpBecomePinned(Object entry) { return permitted; }
        @Override public void setEntryPinned(Object entry, boolean value) { pinned = value; }
    }
    @Test public void returnPathRequiresExactCurrentEntryAndCurrentSystemPolicy() throws Exception {
        var app=org.robolectric.RuntimeEnvironment.getApplication();
        var n=new android.app.Notification.Builder(app,"test").setSmallIcon(android.R.drawable.stat_notify_chat).build();
        var event=new StatusBarNotification(app.getPackageName(),app.getPackageName(),1,null,10100,1,0,
                n,android.os.Process.myUserHandle(),100);
        Restorable manager=new Restorable();manager.current.mEntry.mSbn=event;
        org.robolectric.util.ReflectionHelpers.setStaticField(HeadsUpHooks.class,"manager",manager);
        try {
            Method factory=HeadsUpHooks.class.getDeclaredMethod("returnPath",Object.class,Method.class,Object[].class,StatusBarNotification.class);
            factory.setAccessible(true);
            Method pin=Restorable.class.getMethod("setEntryPinned",Object.class,boolean.class);
            var path=(HeadsUpHooks.NativeRestore)factory.invoke(null,manager,pin,new Object[]{manager.current,true},event);
            assertNotNull(path);assertTrue(path.valid());
            manager.permitted=false;assertFalse(path.restore());assertFalse(manager.pinned);
            manager.permitted=true;assertTrue(path.restore());assertTrue(manager.pinned);
            manager.current=new HeadsUpEntry();assertFalse(path.valid());assertFalse(path.restore());
        } finally { org.robolectric.util.ReflectionHelpers.setStaticField(HeadsUpHooks.class,"manager",null); }
    }
    public static class BrokenGetter {
        public StatusBarNotification getSbn() { throw new IllegalStateException(); }
        public Object entry;
    }
    @Test public void failedExtractionAndUnknownArgumentsLeaveNoClaim() {
        assertNull(HeadsUpHooks.notificationOf(null));
        assertNull(HeadsUpHooks.notificationOf(new Object()));
        assertNull(HeadsUpHooks.notificationOf(new BrokenGetter()));
    }
}
