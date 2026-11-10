package dev.android.dynamicisland.island.window;

import static org.junit.Assert.*;
import android.os.Handler;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import dev.android.dynamicisland.island.material.MaterialFrame;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class MaterialCaptureLifecycleTest {
    @Test public void busyRequestsNeverQueueAndStopCancelsScheduledWork() {
        List<MaterialFrame> delivered=new ArrayList<>();
        MaterialCapture c=new MaterialCapture(new View(RuntimeEnvironment.getApplication()),delivered::add);
        try {
            ReflectionHelpers.setField(c,"active",true);ReflectionHelpers.setField(c,"busy",true);
            Handler handler=ReflectionHelpers.getField(c,"main");Runnable tick=ReflectionHelpers.getField(c,"tick");
            for(int i=0;i<10;i++) {ReflectionHelpers.callInstanceMethod(c,"request");c.refresh();}
            assertFalse(handler.hasCallbacks(tick));
            handler.postDelayed(tick,100);assertTrue(handler.hasCallbacks(tick));
            c.stop();assertFalse(handler.hasCallbacks(tick));
            assertEquals(1,delivered.size());assertNull(delivered.get(0));
            c.refresh();assertFalse(handler.hasCallbacks(tick));
        } finally {c.destroy();}
    }
}
