package dev.android.dynamicisland.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.android.dynamicisland.xposed.hooks.SystemUiHooks;

/**
 * LSPosed entry point (named in {@code assets/xposed_init}). Installs the hooks once, in the main
 * System UI process only, and not at all when {@link CrashGuard} has switched the module off.
 */
public final class Entry implements IXposedHookLoadPackage {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static boolean installed;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        boolean mainSystemUi = SYSTEM_UI.equals(lpparam.packageName) && SYSTEM_UI.equals(lpparam.processName);
        if (!mainSystemUi || installed) {
            return;
        }
        installed = true;
        Diagnostics.fact("entry", "module loaded into " + lpparam.processName);
        if (!CrashGuard.enter(lpparam.appInfo)) {
            Diagnostics.stage("crash guard", false, new IllegalStateException("switched the module off for this System UI process"));
            return;
        }
        Diagnostics.stage("crash guard", true, null);
        try {
            SystemUiHooks.install(lpparam.classLoader);
            Diagnostics.stage("hooks", true, null);
        } catch (Throwable t) {
            Diagnostics.stage("hooks", false, t);
            XLog.e("install failed", t);
        }
    }
}
