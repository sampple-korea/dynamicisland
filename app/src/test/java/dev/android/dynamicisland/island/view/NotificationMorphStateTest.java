package dev.android.dynamicisland.island.view;

import static org.junit.Assert.*;
import org.junit.Test;

public class NotificationMorphStateTest {
    @Test public void transitionsSettleAndClearIdentity() {
        NotificationMorphState s = new NotificationMorphState();
        s.show("first", 100);
        assertEquals(NotificationMorphState.Phase.ENTERING, s.phase());
        assertEquals(1f, s.progress(480), 0f);
        assertEquals(NotificationMorphState.Phase.VISIBLE, s.phase());
        s.hide(500);
        assertEquals(0f, s.progress(860), 0f);
        assertEquals(NotificationMorphState.Phase.INACTIVE, s.phase());
        assertNull(s.key());
    }

    @Test public void interruptionsKeepCurrentProgressAndReplaceIdentity() {
        NotificationMorphState s = new NotificationMorphState();
        s.show("first", 0);
        float entering = s.progress(150);
        s.hide(150);
        assertEquals(entering, s.progress(150), 0f);
        float exiting = s.progress(190);
        s.show("second", 190);
        assertEquals(exiting, s.progress(190), 0f);
        assertEquals("second", s.key());
        assertEquals(1f, s.progress(1000), 0f);
        s.clear();
        assertEquals(NotificationMorphState.Phase.INACTIVE, s.phase());
    }
}
