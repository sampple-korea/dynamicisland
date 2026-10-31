package dev.android.dynamicisland.island.source;

/** Monotonic recording clock. A rolling notification timestamp is not a new recording start. */
final class RecordingClock {
    private static final long RECENT_MS = 1500L;
    private static final long CORRECTION_MS = 500L;
    private long startElapsed;
    private long systemBase;
    private long pendingBase;
    private long pendingSince;

    void start(long base, boolean fromSystem, long wall, long elapsed) {
        startElapsed = elapsed - Math.max(0L, wall - base);
        systemBase = fromSystem ? base : 0L;
        pendingBase = 0L;
    }

    void update(long base, long wall, long elapsed) {
        if (base == systemBase) {
            pendingBase = 0L;
            return;
        }
        long age = wall - base;
        long shift = elapsed - Math.max(0L, age) - startElapsed;
        if (Math.abs(shift) <= CORRECTION_MS) {
            // Ignore sub-second timestamp jitter instead of accumulating it into repeated resets.
            systemBase = base;
            pendingBase = 0L;
            return;
        }
        boolean resetsToNow = age >= -CORRECTION_MS && age <= RECENT_MS && shift > CORRECTION_MS;
        if (pendingBase == base) {
            // Stability is measured since observation, not from the candidate's timestamp age.
            if (elapsed - pendingSince < RECENT_MS) return;
        } else if (resetsToNow) {
            pendingBase = base;
            pendingSince = elapsed;
            return;
        }
        startElapsed = elapsed - Math.max(0L, age);
        systemBase = base;
        pendingBase = 0L;
    }

    boolean matchesSystemBase(long base) {
        return base > 0L && base == systemBase && pendingBase == 0L;
    }

    long elapsedBase() {
        return startElapsed;
    }

    long base(long wall, long elapsed) {
        return wall - Math.max(0L, elapsed - startElapsed);
    }
}
