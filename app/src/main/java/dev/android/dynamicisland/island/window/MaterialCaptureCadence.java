package dev.android.dynamicisland.island.window;

/** Main-thread capture budget. One completion is one observation; no queued samples or timers. */
final class MaterialCaptureCadence {
    private static final long[] PERIODS={34,50,67};
    private int level,overBudget;
    private long recoverySince=-1;
    private double averageCost;
    long periodMs() { return PERIODS[level]; }
    double averageCostMs() { return averageCost; }
    void reset() { level=overBudget=0;recoverySince=-1;averageCost=0; }
    void completed(long costMs,long now) {
        averageCost=averageCost==0 ? costMs : averageCost*.8+costMs*.2;
        if(costMs>periodMs()*.8) {
            recoverySince=-1;
            if(++overBudget>=3) { level=Math.min(PERIODS.length-1,level+1);overBudget=0; }
        } else {
            overBudget=0;
            if(level>0 && costMs<PERIODS[level-1]*.6) {
                if(recoverySince<0) recoverySince=now;
                if(now-recoverySince>=2000) {level--;recoverySince=-1;}
            } else recoverySince=-1;
        }
    }
    long delayAfter(long elapsedMs) { return Math.max(1,periodMs()-Math.max(0,elapsedMs)); }
}
