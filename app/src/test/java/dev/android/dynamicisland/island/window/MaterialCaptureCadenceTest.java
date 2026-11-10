package dev.android.dynamicisland.island.window;

import static org.junit.Assert.*;
import org.junit.Test;

public class MaterialCaptureCadenceTest {
    @Test public void sustainedCostReducesRateAndFastWindowRecoversOneLevel() {
        MaterialCaptureCadence c=new MaterialCaptureCadence();
        assertEquals(34,c.periodMs());
        c.completed(30,0);c.completed(30,34);assertEquals(34,c.periodMs());
        c.completed(30,68);assertEquals(50,c.periodMs());
        c.completed(45,118);c.completed(45,168);c.completed(45,218);assertEquals(67,c.periodMs());
        c.completed(10,300);c.completed(10,2299);assertEquals(67,c.periodMs());
        c.completed(10,2300);assertEquals(50,c.periodMs());
        c.completed(10,2350);c.completed(10,4350);assertEquals(34,c.periodMs());
    }
    @Test public void isolatedSlowSamplesDoNotThrottleAndRecoveryRequiresContinuousBudget() {
        MaterialCaptureCadence c=new MaterialCaptureCadence();
        for(int i=0;i<8;i++){c.completed(30,i*100);c.completed(10,i*100+34);}
        assertEquals(34,c.periodMs());
        c.completed(40,1000);c.completed(40,1050);c.completed(40,1100);
        c.completed(10,1200);c.completed(25,3100);c.completed(10,3200);
        assertEquals(50,c.periodMs());c.completed(10,5200);assertEquals(34,c.periodMs());
    }
    @Test public void schedulesFromElapsedCostAndResetRestoresFreshBudget() {
        MaterialCaptureCadence c=new MaterialCaptureCadence();
        assertEquals(24,c.delayAfter(10));assertEquals(1,c.delayAfter(200));
        for(int i=0;i<20;i++)c.completed(100,i*100);
        assertEquals(67,c.periodMs());assertTrue(c.averageCostMs()>0);
        c.reset();assertEquals(34,c.periodMs());assertEquals(0,c.averageCostMs(),0);
    }
}
