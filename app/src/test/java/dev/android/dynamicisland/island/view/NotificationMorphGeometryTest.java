package dev.android.dynamicisland.island.view;

import static org.junit.Assert.*;
import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class NotificationMorphGeometryTest {
    @Test public void destinationStaysWithinScreenAndReservesCameraSpace() {
        RectF from = new RectF(270, 20, 330, 50), out = new RectF();
        NotificationMorphGeometry.expanded(from, 360, 12, 400, 100, 35, out);
        assertEquals(12, out.left, 0);
        assertEquals(348, out.right, 0);
        assertEquals(20, out.top, 0);
        assertEquals(155, out.bottom, 0);
    }

    @Test public void interpolationStartsAndEndsAtExactBounds() {
        RectF from = new RectF(100, 20, 160, 50);
        RectF to = new RectF(12, 20, 348, 160), out = new RectF();
        NotificationMorphGeometry.interpolate(from, to, 0, out);
        assertEquals(from, out);
        NotificationMorphGeometry.interpolate(from, to, .5f, out);
        assertEquals(56, out.left, 0);
        assertEquals(105, out.bottom, 0);
        NotificationMorphGeometry.interpolate(from, to, 1, out);
        assertEquals(to, out);
    }
}
