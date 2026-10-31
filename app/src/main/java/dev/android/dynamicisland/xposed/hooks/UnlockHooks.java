package dev.android.dynamicisland.xposed.hooks;

import dev.android.dynamicisland.island.source.UnlockSource;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;

/**
 * Feeds the unlock notice from System UI's {@code KeyguardUpdateMonitor}: remembers the instance when the phone starts
 * waking up, and notes when a face or a fingerprint is accepted. The hooks only read; they change nothing.
 */
final class UnlockHooks {
    private static final String MONITOR = "com.android.keyguard.KeyguardUpdateMonitor";

    private UnlockHooks() { }

    static void install(ClassLoader loader) {
        int hooked = Refl.hookAll(loader, MONITOR, "dispatchStartedWakingUp",
                SafeHook.after("unlock notice monitor", p -> UnlockSource.rememberMonitor(p.thisObject)));
        int face = Refl.hookAll(loader, MONITOR, "onFaceAuthenticated",
                SafeHook.after("unlock notice face", p -> UnlockSource.biometricAuthenticated(true)));
        int finger = Refl.hookAll(loader, MONITOR, "onFingerprintAuthenticated",
                SafeHook.after("unlock notice fingerprint", p -> UnlockSource.biometricAuthenticated(false)));
        Diagnostics.fact("unlock notice", hooked > 0 ? "keyguard monitor hooked (face " + face + ", fingerprint " + finger + ")"
                : "keyguard monitor not found; the card says PIN");
    }
}
