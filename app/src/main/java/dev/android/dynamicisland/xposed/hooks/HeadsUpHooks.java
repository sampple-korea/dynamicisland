package dev.android.dynamicisland.xposed.hooks;

import android.service.notification.StatusBarNotification;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import android.os.Looper;
import dev.android.dynamicisland.xposed.Diagnostics;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import dev.android.dynamicisland.island.IslandController;
import dev.android.dynamicisland.xposed.Refl;
import dev.android.dynamicisland.xposed.SafeHook;
import dev.android.dynamicisland.xposed.XLog;

/**
 * Takes over the stock heads-up banner. {@code HeadsUpManagerImpl.shouldHeadsUpBecomePinned} is the
 * single place that decides whether a heads-up notification is actually shown on top of the screen
 * (it is the same switch System UI flips when the shade is open); answering "not pinned" keeps the
 * notification in the shade and keeps its sound, but no banner appears, because the island shows it.
 * The island only claims notifications it is ready to display right now, everything else stays stock.
 */
public final class HeadsUpHooks {
    public enum Decision { NATIVE, RESERVED, PRESENTED }
    public interface NativeRestore {
        boolean valid();
        boolean restore();
    }
    private static volatile boolean replaying;
    private static boolean mainThread() { return Looper.myLooper() == Looper.getMainLooper(); }
    private static final String MANAGER = "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl|com.android.systemui.statusbar.phone.HeadsUpManagerPhone|com.android.systemui.statusbar.policy.HeadsUpManager";

    /** The heads-up manager, kept so that a banner that already came up can be taken away again. */
    private static volatile Object manager;
    private static final int MAX_ASKED_LOGS = 10;
    private static int askedLogs;
    private static final java.util.Map<Object, Integer> recoveryAttempts = new java.util.WeakHashMap<>();

    private HeadsUpHooks() {
    }

    static Set<Method> declarations(Class<?>... classes) {
        Set<Method> found = new LinkedHashSet<>();
        for (Class<?> type : classes) {
            for (Class<?> level = type; level != null; level = level.getSuperclass()) {
                Method[] declared;
                try { declared = level.getDeclaredMethods(); }
                catch (LinkageError | SecurityException unavailable) {
                    XLog.once("heads-up declarations " + level.getName(), unavailable);
                    continue;
                }
                for (Method m : declared) {
                    Class<?>[] t = m.getParameterTypes();
                    if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                            || java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                    boolean decision = m.getName().equals("shouldHeadsUpBecomePinned")
                            && m.getReturnType() == boolean.class && t.length == 1 && !t[0].isPrimitive();
                    boolean pin = m.getName().equals("setEntryPinned") && m.getReturnType() == void.class
                            && (t.length == 2 || t.length == 3 && t[2] == String.class)
                            && !t[0].isPrimitive() && (t[1] == boolean.class || statusValue(t[1], "NotPinned") != null
                            && statusValue(t[1], "PinnedBySystem") != null);
                    boolean update = m.getName().equals("updateNotificationInternal")
                            && m.getReturnType() == void.class && t.length >= 1 && t.length <= 3 && !t[0].isPrimitive()
                            && (t.length == 1 || t[1] == boolean.class)
                            && (t.length < 3 || t[2] == String.class);
                    boolean listener = m.getName().equals("addListener") && m.getReturnType() == void.class && t.length == 1;
                    if (decision || pin || update || listener) found.add(m);
                }
            }
        }
        return found;
    }

    static void install(ClassLoader cl) {
        Set<Method> methods = new LinkedHashSet<>();
        for (String name : MANAGER.split("\\|")) {
            Class<?> type = Refl.findClass(name, cl);
            if (type != null) methods.addAll(declarations(type));
        }
        int installed = 0;
        for (Method method : methods) {
            try {
                method.setAccessible(true);
                XC_MethodHook hook = switch (method.getName()) {
                    case "shouldHeadsUpBecomePinned" -> SafeHook.after("heads-up decision", HeadsUpHooks::afterPinDecision);
                    case "setEntryPinned" -> SafeHook.before("heads-up pin", HeadsUpHooks::beforeSetPinned);
                    default -> SafeHook.after("heads-up reconcile", p -> {
                        if (!mainThread() || replaying || p.hasThrowable()) return;
                        manager = p.thisObject;
                        executed(p);
                        StatusBarNotification sbn = p.args.length == 0 ? null : notificationOf(p.args[0]);
                        IslandController controller = IslandController.get();
                        if (controller != null && sbn != null) controller.updateHeadsUp(sbn, returnPathForEvent(p.thisObject, sbn));
                        if (sbn != null && owned(sbn.getKey())) dismissStock(sbn.getKey());
                    });
                };
                XposedBridge.hookMethod(method, hook);
                installed++;
                XLog.event("heads-up installed " + method.toGenericString());
            } catch (Throwable failure) { XLog.once("heads-up install " + method.getName(), failure); }
        }
        Diagnostics.fact("heads-up capability", installed + " hooks installed; execution unconfirmed");
    }

    private static Object statusValue(Class<?> type, String name) {
        try {
            java.lang.reflect.Field field = type.getField(name);
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) return null;
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }

    private static void executed(XC_MethodHook.MethodHookParam p) {
        Diagnostics.fact("heads-up executed " + p.method.getName(), p.method.toString());
        if (askedLogs++ < MAX_ASKED_LOGS) XLog.event("heads-up executed " + p.method.toString());
    }

    /** Only dismiss a banner while the matching posting is still actually displayed by our overlay. */
    private static boolean owned(String key) {
        IslandController controller = IslandController.get();
        return controller != null && controller.ownsHeadsUp(key);
    }

    public static String dismissStock(String key) {
        if (!mainThread()) return "off main thread";
        Object heads = manager;
        Object event = IslandController.get() == null ? null : IslandController.get().headsUpEvent(key);
        if (heads == null || key == null || !owned(key)) return "not currently owned by the island";
        try {
            if (removalMethod(heads.getClass()) == null) return "unsupported removal API";
            if (!attemptRemoval(heads, key, event)) return "retry budget exhausted";
            Boolean up = status(heads, key);
            if (Boolean.FALSE.equals(up)) return "stock banner removed";
            for (long delay : new long[]{150, 500}) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (manager != heads || !owned(key) || IslandController.get() == null
                            || IslandController.get().headsUpEvent(key) != event || Boolean.FALSE.equals(status(heads, key))) return;
                    try {
                        attemptRemoval(heads, key, event);
                        if (delay == 500 && !Boolean.FALSE.equals(status(heads, key))) {
                            Diagnostics.fact("heads-up recovery", "bounded retries finished; removal unconfirmed");
                        }
                    } catch (Throwable t) { XLog.once("heads-up retry", t); }
                }, delay);
            }
            return up == null ? "removal requested; state unknown" : "removal requested; retrying";
        } catch (Throwable t) {
            XLog.once("HeadsUpHooks.dismissStock", t);
            return "failed: " + t.getClass().getSimpleName();
        }
    }

    private static boolean attemptRemoval(Object heads, String key, Object event) throws ReflectiveOperationException {
        int attempts = recoveryAttempts.getOrDefault(event, 0);
        if (attempts >= 3) return false;
        recoveryAttempts.put(event, attempts + 1);
        boolean requested = remove(heads, key);
        Diagnostics.fact("heads-up recovery", "attempt " + (attempts + 1) + "; state " + status(heads, key));
        return requested;
    }

    private static void remove(Object heads, Method method, String key) throws ReflectiveOperationException {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = types.length == 2 ? new Object[]{key, true}
                : types[1] == boolean.class ? new Object[]{key, true, "dynamic island"}
                : new Object[]{key, "dynamic island", true};
        method.invoke(heads, args);
    }

    /** Match types, not arity: Samsung and AOSP/Nothing order the reason and immediate flag differently. */
    private static Method removalMethod(Class<?> type) {
        Class<?>[][] supported = {
                {String.class, String.class, boolean.class},
                {String.class, boolean.class, String.class},
                {String.class, boolean.class}
        };
        for (Class<?>[] signature : supported) {
            for (Class<?> level = type; level != null; level = level.getSuperclass()) {
                for (Method method : level.getDeclaredMethods()) {
                    if ((method.getReturnType() == boolean.class || method.getReturnType() == void.class)
                            && method.getName().equals("removeNotification")
                            && Arrays.equals(method.getParameterTypes(), signature)) {
                        method.setAccessible(true);
                        return method;
                    }
                }
            }
        }
        return null;
    }

    private static boolean remove(Object heads, String key) throws ReflectiveOperationException {
        Method method = removalMethod(heads.getClass());
        if (method == null) return false;
        remove(heads, method, key);
        return true;
    }

    /** null means the build has no readable accessor, never a proven removal. */
    static Boolean status(Object heads, String key) {
        if (Refl.method(heads.getClass(), "getHeadsUpEntry", 1) != null) {
            try { return Refl.method(heads.getClass(), "getHeadsUpEntry", 1).invoke(heads, key) != null; }
            catch (ReflectiveOperationException | RuntimeException unreadable) { return null; }
        }
        for (String accessor : new String[]{"getHeadsUpEntryList", "getAllEntries"}) {
            if (Refl.method(heads.getClass(), accessor, 0) == null) continue;
            Object entries;
            try { entries = Refl.method(heads.getClass(), accessor, 0).invoke(heads); }
            catch (ReflectiveOperationException | RuntimeException unreadable) { continue; }
            if (entries instanceof java.util.Map) entries = ((java.util.Map<?, ?>) entries).values();
            if (!(entries instanceof Iterable)) continue;
            boolean readable = true;
            for (Object entry : (Iterable<?>) entries) {
                StatusBarNotification notification = notificationOf(entry);
                if (notification == null) readable = false;
                else if (key.equals(notification.getKey())) return true;
            }
            if (readable) return false;
        }
        return null;
    }

    static StatusBarNotification notificationOf(Object entry) {
        for (int depth = 0; entry != null && depth < 3; depth++) {
            if (entry instanceof StatusBarNotification) return (StatusBarNotification) entry;
            if (Refl.method(entry.getClass(), "getSbn", 0) != null) {
                Object value = Refl.call(entry, "getSbn");
                if (value instanceof StatusBarNotification) return (StatusBarNotification) value;
            }
            Object nested = null;
            for (String name : new String[]{"mSbn", "sbn", "mEntry", "entry"}) {
                if (Refl.field(entry.getClass(), name) != null) {
                    Object value = Refl.get(entry, name);
                    if (value != null) { nested = value; break; }
                }
            }
            entry = nested;
        }
        return null;
    }

    static Object unpinned(Object request) {
        if (Boolean.TRUE.equals(request)) return false;
        if (request == null) return null;
        Object system = statusValue(request.getClass(), "PinnedBySystem");
        return request == system ? statusValue(request.getClass(), "NotPinned") : null;
    }

    /** A reservation can only replay this exact pin on the manager's still-current entry. */
    private static NativeRestore returnPath(XC_MethodHook.MethodHookParam p, StatusBarNotification sbn) {
        return returnPath(p.thisObject, (Method) p.method, p.args.clone(), sbn);
    }

    private static NativeRestore returnPath(Object heads, Method pin, Object[] args, StatusBarNotification sbn) {
        Method getter = Refl.method(heads.getClass(), "getHeadsUpEntry", 1);
        Object entry = args[0];
        Object nested = Refl.get(entry, "mEntry");
        Object candidate = nested == null ? entry : nested;
        Method policy = null;
        for (Method method : declarations(heads.getClass())) {
            if (method.getName().equals("shouldHeadsUpBecomePinned")
                    && method.getParameterTypes()[0].isInstance(candidate)) { policy = method; break; }
        }
        if (getter == null || policy == null) return null;
        Method decision = policy;
        try {
            pin.setAccessible(true); getter.setAccessible(true); decision.setAccessible(true);
            if (getter.invoke(heads, sbn.getKey()) != entry) return null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
        return new NativeRestore() {
            public boolean valid() {
                if (!mainThread() || manager != heads) return false;
                try {
                    StatusBarNotification current = notificationOf(entry);
                    return getter.invoke(heads, sbn.getKey()) == entry && current != null
                            && current.getUserId() == sbn.getUserId() && current.getPostTime() == sbn.getPostTime()
                            && current.getNotification() == sbn.getNotification();
                } catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
            }
            public boolean restore() {
                if (!valid()) return false;
                replaying = true;
                try {
                    // Respect current shade, snooze, lock screen and ROM policy before re-pinning.
                    if (!Boolean.TRUE.equals(decision.invoke(heads, candidate))) return false;
                    pin.invoke(heads, args);
                    return true;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    XLog.once("heads-up return failed", failure);
                    return false;
                } finally { replaying = false; }
            }
        };
    }

    private static void leaveNativeOffThread(XC_MethodHook.MethodHookParam p) {
        IslandController controller = IslandController.get();
        if (controller != null && p.args.length > 0) controller.decideHeadsUp(notificationOf(p.args[0]), null);
        Diagnostics.fact("heads-up fallback", "off main thread; native preserved");
    }

    private static void beforeSetPinned(XC_MethodHook.MethodHookParam p) {
        if (!mainThread()) { leaveNativeOffThread(p); return; }
        if (replaying) return;
        manager = p.thisObject;
        executed(p);
        if (p.args.length < 2) return;
        // Unknown statuses and explicit user requests retain native behavior.
        if (p.args.length > 2 && p.args[2] instanceof String
                && ((String) p.args[2]).toLowerCase(java.util.Locale.ROOT).contains("user")) return;
        Object off = unpinned(p.args[1]);
        if (off == null) return;
        StatusBarNotification sbn = notificationOf(p.args[0]);
        IslandController controller = IslandController.get();
        if (controller == null || sbn == null) return;
        Decision result = controller.decideHeadsUp(sbn, returnPath(p, sbn));
        Diagnostics.fact("heads-up decision", result.name());
        if (result != Decision.NATIVE) p.args[1] = off;
    }

    private static NativeRestore returnPathForEvent(Object heads, StatusBarNotification sbn) {
        NativeRestore restore = null;
        Method getter = Refl.method(heads.getClass(), "getHeadsUpEntry", 1);
        if (getter != null) {
            try {
                Object entry = getter.invoke(heads, sbn.getKey());
                for (Method pin : declarations(heads.getClass())) {
                    if (!pin.getName().equals("setEntryPinned") || entry == null
                            || !pin.getParameterTypes()[0].isInstance(entry)) continue;
                    Class<?>[] types = pin.getParameterTypes();
                    Object system = types[1] == boolean.class ? true : statusValue(types[1], "PinnedBySystem");
                    Object[] args = types.length == 2 ? new Object[]{entry, system}
                            : new Object[]{entry, system, "dynamic island return"};
                    restore = returnPath(heads, pin, args, sbn);
                    if (restore != null) break;
                }
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                Diagnostics.fact("heads-up reservation", "return path unavailable");
            }
        }
        return restore;
    }

    private static void afterPinDecision(XC_MethodHook.MethodHookParam p) {
        if (!mainThread()) { leaveNativeOffThread(p); return; }
        if (replaying) return;
        manager = p.thisObject;
        executed(p);
        if (p.hasThrowable() || !Boolean.TRUE.equals(p.getResult()) || p.args.length != 1) return;
        StatusBarNotification sbn = notificationOf(p.args[0]);
        IslandController controller = IslandController.get();
        if (controller == null || sbn == null) return;
        NativeRestore restore = returnPathForEvent(p.thisObject, sbn);
        Decision result = controller.decideHeadsUp(sbn, restore);
        Diagnostics.fact("heads-up decision", result.name());
        if (result != Decision.NATIVE) p.setResult(false);
    }
}
