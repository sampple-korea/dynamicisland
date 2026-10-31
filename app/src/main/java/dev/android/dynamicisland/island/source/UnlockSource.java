package dev.android.dynamicisland.island.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.IslandText;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import java.util.List;

/**
 * "Unlocked by ..." notice, in the spirit of the iPhone's unlock line.
 *
 * <p>The method is inferred from what the lock screen did just before it went away.
 * {@link #biometricAuthenticated(boolean)} and {@link #deviceTrusted()} are called from the System UI
 * hooks (see {@code SystemUiHooks}); {@code ACTION_USER_PRESENT} fires for every unlock whatever the
 * method, so it is the trigger and the two timestamps are what decide the wording. If neither a
 * biometric nor a trusted device was seen within {@link #MATCH_MS} the only way left is the PIN,
 * pattern or password.
 *
 * <p>Everything here fails soft: if the keyguard hooks never fire, the notice still appears and simply
 * reports the PIN.
 */
public final class UnlockSource {
    /** How long the notice stays on the island. */
    private static final long CARD_MS = 2600L;
    /** A biometric or trust event this recent is taken as the reason for the unlock. */
    private static final long MATCH_MS = 2500L;

    private static final int NONE = 0, FINGERPRINT = 1, FACE = 2, PIN = 3, WATCH = 4;

    // Written by the System UI hooks, which run on the keyguard's own threads.
    private static volatile int lastBiometric = NONE;
    private static volatile long lastBiometricAt;
    private static volatile long lastTrustedAt;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private IslandItem showing;
    private long hideAt;
    /** Drops the notice when its time is up. */
    private void expire() {
        showing = null;
        onChange.run();
    }

    public UnlockSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
    }

    /**
     * Called from the keyguard hook when a biometric authenticates.
     *
     * @param face true for face, false for fingerprint
     */
    public static void biometricAuthenticated(boolean face) {
        lastBiometric = face ? FACE : FINGERPRINT;
        lastBiometricAt = SystemClock.uptimeMillis();
    }

    /** Called from the keyguard hook when the device becomes trusted (a paired watch, Smart Lock). */
    public static void deviceTrusted() {
        lastTrustedAt = SystemClock.uptimeMillis();
    }

    /** For diagnostics: which method the next unlock would report. */
    public static String lastMethod() {
        long now = SystemClock.uptimeMillis();
        if (lastBiometric != NONE && now - lastBiometricAt <= MATCH_MS) {
            return lastBiometric == FACE ? "face" : "fingerprint";
        }
        if (lastTrustedAt > 0 && now - lastTrustedAt <= MATCH_MS) {
            return "watch";
        }
        return "pin";
    }

    public void start() {
        try {
            ctx.registerReceiver(present, new IntentFilter(Intent.ACTION_USER_PRESENT));
            XLog.i("unlock notice: listening");
        } catch (Throwable t) {
            XLog.e("unlock notice not registered", t);
        }
    }

    private final BroadcastReceiver present = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            try {
                if (!ModuleSettings.unlockNotice()) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                int method;
                if (lastBiometric != NONE && now - lastBiometricAt <= MATCH_MS) {
                    method = lastBiometric;
                } else if (lastTrustedAt > 0 && now - lastTrustedAt <= MATCH_MS) {
                    method = WATCH;
                } else {
                    method = PIN;
                }
                show(method, now);
            } catch (Throwable t) {
                XLog.once("unlock notice", t);
            }
        }
    };

    private void show(int method, long now) {
        IslandItem it = new IslandItem("event:unlock");
        it.kind = IslandItem.Kind.EVENT;
        it.eventLayout = IslandItem.EventLayout.STATUS;
        it.iconIsImage = false;
        it.pillColor = 0;
        String label;
        GlyphIcon.Kind glyph;
        switch (method) {
            case FINGERPRINT:
                label = "island_unlocked_by_fingerprint";
                glyph = GlyphIcon.Kind.FINGERPRINT;
                break;
            case FACE:
                label = "island_unlocked_by_face";
                glyph = GlyphIcon.Kind.FACE;
                break;
            case WATCH:
                label = "island_unlocked_by_watch";
                glyph = GlyphIcon.Kind.WATCH;
                break;
            default:
                label = "island_unlocked_by_pin";
                glyph = GlyphIcon.Kind.PIN;
                break;
        }
        it.title = IslandText.get(ctx, label);
        it.compactLabel = IslandText.get(ctx, "island_unlocked");
        it.icon = GlyphIcon.of(glyph);
        it.accent = 0xFF9BE8A8;
        it.postTime = now;
        it.expiresAtUptime = now + CARD_MS;
        // open like a notification rather than sitting as a pill; IslandController reads this
        it.peekMillis = CARD_MS;
        showing = it;
        hideAt = it.expiresAtUptime;
        main.removeCallbacks(this::expire);
        main.postDelayed(this::expire, CARD_MS + 50L);
        onChange.run();
    }

    /** Adds the notice while it is alive; the refresh that removes it is scheduled by show(). */
    public void collect(List<IslandItem> out) {
        if (showing == null) {
            return;
        }
        if (SystemClock.uptimeMillis() >= hideAt || !ModuleSettings.unlockNotice()) {
            showing = null;
            return;
        }
        out.add(showing);
    }
}
