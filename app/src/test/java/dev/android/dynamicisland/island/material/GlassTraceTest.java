package dev.android.dynamicisland.island.material;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GlassTraceTest {
    private static final int ALL = GlassTrace.FROSTED | GlassTrace.HARDWARE | GlassTrace.BACKDROP | GlassTrace.AVAILABLE
            | GlassTrace.SHOWN;

    @Test public void framesThatLookTheSameAreCountedNotStored() {
        GlassTrace trace = new GlassTrace();
        for (int i = 0; i < 100; i++) trace.record(1000 + i, ALL, 1f);
        assertEquals(100, trace.frames());
        String summary = trace.summary(1200);
        assertTrue(summary, summary.contains("1 changes"));
        assertTrue(summary, summary.contains("x100"));
    }

    @Test public void aChangeShowsWhichFlagWentAndWhen() {
        GlassTrace trace = new GlassTrace();
        trace.record(1000, ALL, 1f);
        trace.record(2000, ALL & ~GlassTrace.FROSTED, 1f);
        trace.record(3000, ALL, .4f);
        String summary = trace.summary(4000);
        assertTrue(summary, summary.contains("[-3.0s !held !blending !darkTone resp 1.0]"));
        assertTrue(summary, summary.contains("[-2.0s !frosted !held !blending !darkTone resp 1.0]"));
        assertTrue(summary, summary.contains("[-1.0s !held !blending !darkTone resp 0.4]"));
    }

    @Test public void onlyTheNewestChangesAreKept() {
        GlassTrace trace = new GlassTrace();
        for (int i = 0; i < 200; i++) trace.record(i * 100L, (i % 2 == 0) ? ALL : ALL | GlassTrace.HELD, 1f);
        String summary = trace.summary(30_000);
        assertTrue(summary, summary.contains("40 changes"));
        assertFalse(summary, summary.contains("[-30.0s"));
    }

    @Test public void noBackdropShowsAsNone() {
        GlassTrace trace = new GlassTrace();
        trace.record(0, ALL & ~GlassTrace.BACKDROP, -1f);
        assertTrue(trace.summary(100), trace.summary(100).contains("resp none"));
    }

    @Test public void emptyTraceSaysSo() {
        assertEquals("no frame drawn yet", new GlassTrace().summary(0));
    }
}
