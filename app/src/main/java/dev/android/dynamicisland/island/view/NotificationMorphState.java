package dev.android.dynamicisland.island.view;

/** Visual progress only. Notification ownership and timeout remain with the existing peek flow. */
final class NotificationMorphState {
    enum Phase { INACTIVE, ENTERING, VISIBLE, EXITING }
    private static final long ENTER_MS = 380;
    private static final long EXIT_MS = 360;
    private Phase phase = Phase.INACTIVE;
    private String key;
    private float from;
    private long started;
    private long duration;

    void show(String nextKey, long now) {
        if (nextKey == null) return;
        float current = progress(now);
        if (nextKey.equals(key) && (phase == Phase.ENTERING || phase == Phase.VISIBLE)) return;
        key = nextKey;
        if (phase == Phase.VISIBLE) return;
        from = current;
        started = now;
        duration = Math.max(1, Math.round(ENTER_MS * (1 - current)));
        phase = Phase.ENTERING;
    }

    void hide(long now) {
        if (phase == Phase.INACTIVE || phase == Phase.EXITING) return;
        from = progress(now);
        started = now;
        duration = Math.max(1, Math.round(EXIT_MS * from));
        phase = Phase.EXITING;
    }

    float progress(long now) {
        if (phase == Phase.INACTIVE) return 0;
        if (phase == Phase.VISIBLE) return 1;
        float t = Math.max(0, Math.min(1, (now - started) / (float) duration));
        float eased = t * t * (3 - 2 * t);
        float value = phase == Phase.ENTERING ? from + (1 - from) * eased : from * (1 - eased);
        if (t >= 1) {
            phase = phase == Phase.ENTERING ? Phase.VISIBLE : Phase.INACTIVE;
            if (phase == Phase.INACTIVE) key = null;
        }
        return value;
    }

    Phase phase() { return phase; }
    String key() { return key; }
    void clear() { phase = Phase.INACTIVE; key = null; from = 0; }
}
