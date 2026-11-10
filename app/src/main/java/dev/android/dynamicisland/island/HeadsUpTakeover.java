package dev.android.dynamicisland.island;

import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.detect.OtpDetector;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.source.PeekSource;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import dev.android.dynamicisland.xposed.hooks.HeadsUpHooks;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides which heads-up banners and which fresh verification codes become a temporary card on the
 * island, and remembers what already got one so nothing is shown twice.
 */
final class HeadsUpTakeover {
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.LinkedHashMap<String, Reservation> reservations = new java.util.LinkedHashMap<>();
    private final Map<String, StatusBarNotification> nativeEvents = java.util.Collections.synchronizedMap(new HashMap<>());
    private StatusBarNotification activeEvent;
    private IslandItem activeItem;
    private boolean reconciling;
    private long nextVersion;
    private long activeVersion;
    private static final class Reservation {
        final StatusBarNotification event;
        final IslandItem item;
        final HeadsUpHooks.NativeRestore restore;
        final long deadline, version;
        Reservation(StatusBarNotification event, IslandItem item, HeadsUpHooks.NativeRestore restore,
                long version, long deadline) {
            this.event = event; this.item = item; this.restore = restore;
            this.version = version; this.deadline = deadline;
        }
    }
    private static boolean mainThread() {
        return android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
    }
    private static boolean sameEvent(StatusBarNotification a, StatusBarNotification b) {
        return a != null && b != null && a.getUserId() == b.getUserId()
                && a.getKey().equals(b.getKey()) && a.getPostTime() == b.getPostTime()
                && a.getNotification() == b.getNotification();
    }
    private boolean allowed(StatusBarNotification sbn) {
        return sbn != null && sbn.getUser().equals(android.os.Process.myUserHandle())
                && PeekSource.eligible(sbn) && ModuleSettings.appAllowed(sbn.getPackageName())
                && !foregroundBlocked(sbn) && PeekSource.preferred(sbn, snapshot);
    }
    private boolean enabled(IslandItem item) {
        return item.otpCode != null ? ModuleSettings.otpCard()
                : ModuleSettings.headsUpIsland() || ModuleSettings.allNotifications();
    }
    private boolean leftNative(StatusBarNotification sbn) {
        return sameEvent(nativeEvents.get(sbn.getKey()), sbn);
    }
    private HeadsUpHooks.Decision fallback(StatusBarNotification sbn, String reason) {
        if (sbn != null) {
            if (nativeEvents.size() > 128) nativeEvents.clear();
            nativeEvents.put(sbn.getKey(), sbn);
            reservations.remove(sbn.getKey());
        }
        dev.android.dynamicisland.xposed.Diagnostics.fact("heads-up fallback", reason);
        reject(reason);
        return HeadsUpHooks.Decision.NATIVE;
    }
    /** Runs before collection and on visibility changes; never replays removed or superseded events. */
    void reconcile() {
        if (!mainThread() || reconciling) return;
        reconciling = true;
        try {
            for (Reservation r : new java.util.ArrayList<>(reservations.values())) {
                String key = r.event.getKey();
                if (reservations.get(key) != r) continue;
                boolean valid = r.restore.valid();
                boolean nativeAlready = leftNative(r.event);
                if (nativeAlready || !valid || !allowed(r.event) || !enabled(r.item) || !placement.canPresent()
                        || SystemClock.uptimeMillis() >= r.deadline) {
                    reservations.remove(key);
                    fallback(r.event, valid ? "reservation invalidated" : "reservation no longer current");
                    if (valid && !nativeAlready) {
                        boolean restored = r.restore.restore();
                        note(restored ? "reservation returned to system" : "reservation return failed");
                    }
                } else if (sources.peeks.showing() == null) {
                    reservations.remove(key);
                    if (!show(r.event, r.item, r.version)) {
                        fallback(r.event, "reserved presentation rejected");
                        note(r.restore.restore() ? "reservation returned to system" : "reservation return failed");
                    }
                }
            }
        } finally { reconciling = false; }
    }

    /** How long a heads-up card stays open unless the item asks for something else. */
    private static final long DEFAULT_PEEK_MS = 5000;
    /** Wait before the stock heads-up entry of a notification the island took is removed (it is added right after the decision). */
    private static final long STOCK_DISMISS_DELAY_MS = 120;
    /** A verification code is "fresh" (worth a card) for this long after its notification was posted. */
    private static final long OTP_FRESH_MS = 20000;
    /** A notification is "new" for the all-notifications pass for this long after it was posted. */
    private static final long ALL_FRESH_MS = 8000;
    /** Notifications dated this far in the future are still accepted (clock differences between processes). */
    private static final long OTP_CLOCK_SKEW_MS = 5000;
    /** The same code arriving again within this time is ignored. */
    private static final long OTP_REPEAT_WINDOW_MS = 120000;
    /** Bounds of the "already seen" caches; they are simply cleared when exceeded. */
    private static final int MAX_PEEKED = 32;
    private static final int MAX_OTP_CHECKED = 128;
    /** Only the first few card decisions are logged, to keep the log readable. */
    private static final int MAX_OTP_LOGS = 20;

    private final IslandSources sources;
    private final IslandPlacement placement;
    /** Rebuilds the island list immediately (a card is a source item). */
    private final Runnable refreshNow;

    /** Notification key to post time of the posting that already got a card. */
    private final Map<String, StatusBarNotification> peeked = new HashMap<>();
    private final Set<String> otpChecked = new HashSet<>();
    /** Postings the "all notifications" pass already looked at. */
    private final Set<String> allChecked = new HashSet<>();
    private List<StatusBarNotification> snapshot = java.util.Collections.emptyList();
    private int rejectLogs;
    private int otpLogs;
    private String lastOtpCode;
    private long lastOtpAt;

    HeadsUpTakeover(IslandSources sources, IslandPlacement placement, Runnable refreshNow) {
        this.sources = sources;
        this.placement = placement;
        this.refreshNow = refreshNow;
    }

    void setSnapshot(List<StatusBarNotification> notifications) {
        java.util.Set<String> removed = new java.util.HashSet<>();
        for (StatusBarNotification old : snapshot) if (old != null) removed.add(old.getKey());
        for (StatusBarNotification next : notifications) if (next != null) removed.remove(next.getKey());
        sources.peeks.removeNotifications(removed);
        snapshot = new java.util.ArrayList<>(notifications);
        for (String key : removed) {
            reservations.remove(key); nativeEvents.remove(key);
        }
        if (activeEvent != null && sources.peeks.showing() == activeItem) {
            for (StatusBarNotification next : notifications) {
                if (next.getKey().equals(activeEvent.getKey()) && !sameEvent(next, activeEvent)) {
                    if (allowed(next) && placement.canPresent() && !leftNative(next)) {
                        IslandItem updated = sources.peeks.build(next);
                        if (updated != null && enabled(updated)) show(next, updated);
                    }
                    break;
                }
            }
        }
        // An update must release the previous version before it can be claimed again.
        reservations.entrySet().removeIf(e -> notifications.stream().anyMatch(n ->
                n.getKey().equals(e.getKey()) && !sameEvent(n, e.getValue().event)));
        reconcile();
    }

    void onUpdate(StatusBarNotification sbn, HeadsUpHooks.NativeRestore restore) {
        if (!mainThread() || sbn == null) return;
        if (reservations.containsKey(sbn.getKey())) {
            HeadsUpHooks.Decision result = decide(sbn, restore);
            if (result == HeadsUpHooks.Decision.NATIVE && restore != null && restore.valid()) restore.restore();
        }
        if (activeEvent != null && activeEvent.getKey().equals(sbn.getKey())
                && sources.peeks.showing() == activeItem && !sameEvent(activeEvent, sbn)) {
            decide(sbn, null);
        }
        reconcile();
    }

    Object event(String key) { return owns(key) ? activeItem : null; }

    boolean owns(String key) {
        if (!mainThread() || key == null || activeEvent == null || activeItem == null || !placement.canPresent()
                || !key.equals(activeEvent.getKey()) || !allowed(activeEvent) || !enabled(activeItem)
                || sources.peeks.showing() != activeItem || !placement.view().isShowingCard(activeItem)) return false;
        for (StatusBarNotification notification : snapshot) {
            if (notification.getKey().equals(key) && !sameEvent(notification, activeEvent)) return false;
        }
        return true;
    }

    private boolean foregroundBlocked(StatusBarNotification sbn) {
        return !ModuleSettings.foregroundNotifications() && sbn.getPackageName().equals(sources.foregroundPackage());
    }

    /** @return true when the island took the banner (the stock one must stay away) */
    boolean take(StatusBarNotification sbn) {
        return decide(sbn, null) != HeadsUpHooks.Decision.NATIVE;
    }

    HeadsUpHooks.Decision decide(StatusBarNotification sbn, HeadsUpHooks.NativeRestore restore) {
        if (!mainThread()) {
            if (sbn != null) nativeEvents.put(sbn.getKey(), sbn);
            return HeadsUpHooks.Decision.NATIVE;
        }
        try {
            if (!allowed(sbn) || !placement.canPresent()) return fallback(sbn, "ineligible or unavailable");
            if (sameEvent(activeEvent, sbn) && owns(sbn.getKey())) return HeadsUpHooks.Decision.PRESENTED;
            Reservation existing = reservations.get(sbn.getKey());
            if (existing != null && sameEvent(existing.event, sbn)) {
                if (existing.restore.valid() && SystemClock.uptimeMillis() < existing.deadline) return HeadsUpHooks.Decision.RESERVED;
                reservations.remove(sbn.getKey());
                return fallback(sbn, "reservation no longer valid");
            }
            if (leftNative(sbn)) return HeadsUpHooks.Decision.NATIVE;
            IslandItem item = sources.peeks.buildOtp(sbn);
            boolean updatingActive = activeEvent != null && activeEvent.getKey().equals(sbn.getKey())
                    && sources.peeks.showing() == activeItem && placement.view().isShowingCard(activeItem);
            if (item != null && isRepeat(item.otpCode) && !updatingActive) return fallback(sbn, "repeated code");
            if (item == null && (ModuleSettings.headsUpIsland() || ModuleSettings.allNotifications())) {
                item = sources.peeks.buildMessage(sbn);
            }
            if (item == null || !enabled(item)) return fallback(sbn, "disabled or no presentable content");
            IslandItem showing = sources.peeks.showing();
            if (showing != null && !showing.key.equals(item.key)) {
                if (restore == null || !restore.valid()) return fallback(sbn, "no verified return path");
                if (existing == null && reservations.size() >= 4) return fallback(sbn, "reservation queue full");
                reservations.put(sbn.getKey(), new Reservation(sbn, item, restore, ++nextVersion,
                        existing == null ? SystemClock.uptimeMillis() + 30000 : existing.deadline));
                dev.android.dynamicisland.xposed.Diagnostics.fact("heads-up reservations", Integer.toString(reservations.size()));
                main.postDelayed(this::reconcile, 30001);
                note("RESERVED (verified return path)");
                return HeadsUpHooks.Decision.RESERVED;
            }
            reservations.remove(sbn.getKey());
            if (show(sbn, item)) {
                note("PRESENTED (active card confirmed)");
                return HeadsUpHooks.Decision.PRESENTED;
            }
            return fallback(sbn, "presentation rejected");
        } catch (Throwable t) {
            XLog.once("IslandController.peekHeadsUp", t);
            return fallback(sbn, "decision failed");
        }
    }

    /**
     * The "all notifications" setting: a fresh notification that never became a banner (silent, or the system
     * did not ask) still gets a card. Each fresh posting is considered once, oldest first.
     */
    void scanAll(List<StatusBarNotification> snapshot) {
        if (!mainThread() || !ModuleSettings.allNotifications() || !placement.canPresent()) {
            return;
        }
        if (allChecked.size() > MAX_OTP_CHECKED) {
            allChecked.clear();
        }
        long now = System.currentTimeMillis();
        List<StatusBarNotification> fresh = new java.util.ArrayList<>();
        for (StatusBarNotification sbn : snapshot) {
            long age = now - sbn.getPostTime();
            if (age > ALL_FRESH_MS || age < -OTP_CLOCK_SKEW_MS || !allChecked.add(sbn.getKey() + "@" + sbn.getPostTime())) {
                continue;
            }
            if (alreadyHadCard(sbn) || leftNative(sbn) || reservations.containsKey(sbn.getKey()) || !allowed(sbn)) {
                continue;
            }
            fresh.add(sbn);
        }
        if (fresh.isEmpty()) {
            return;
        }
        fresh.sort(java.util.Comparator.comparingLong(StatusBarNotification::getPostTime));
        for (StatusBarNotification notification : fresh) {
            IslandItem item = sources.peeks.build(notification);
            if (item == null) continue;
            if (show(notification, item)) {
                note("card for " + notification.getPackageName() + " from the notification list (stock banner asked to leave: "
                        + HeadsUpHooks.dismissStock(notification.getKey()) + ")");
            } else {
                reject("all notifications: the island could not open");
            }
        }
    }

    private void note(String what) {
        if (rejectLogs < MAX_OTP_LOGS) {
            rejectLogs++;
            XLog.event("notification card: " + what);
        }
    }

    /** Why a banner or a card was left to the stock UI; only the first few are logged. */
    private void reject(String why) {
        if (rejectLogs < MAX_OTP_LOGS) {
            rejectLogs++;
            XLog.event("notification card not shown: " + why);
        }
    }

    /**
     * Codes do not depend on a heads-up banner: a text can arrive silently, or System UI can show it some
     * other way. Every notification new to the list is checked once; a fresh one with a code opens the card.
     */
    void scanForCodes(List<StatusBarNotification> snapshot) {
        if (!mainThread() || !ModuleSettings.otpCard() || !placement.canPresent()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (otpChecked.size() > MAX_OTP_CHECKED) {
            otpChecked.clear();
        }
        for (StatusBarNotification sbn : snapshot) {
            long age = now - sbn.getPostTime();
            if (age > OTP_FRESH_MS || age < -OTP_CLOCK_SKEW_MS || alreadyHadCard(sbn) || leftNative(sbn) || reservations.containsKey(sbn.getKey()) || !allowed(sbn)) {
                continue;
            }
            if (!otpChecked.add(sbn.getKey() + "@" + sbn.getPostTime()) || !PeekSource.eligible(sbn) || !PeekSource.preferred(sbn, snapshot)) {
                continue;
            }
            OtpDetector.Match match = OtpDetector.detect(PeekSource.otpText(sbn.getNotification()));
            // a messages app re-posting the same text (marked read, another device synced) is not a new code
            if (match == null || isRepeat(match.code)) {
                continue;
            }
            if (foregroundBlocked(sbn)) {
                log("not shown: the messages app is open");
            } else if (placement.view().isSuppressedNow()) {
                log("not shown: island hidden (lock screen, full screen or landscape)");
            } else {
                IslandItem item = sources.peeks.buildOtp(sbn);
                if (item != null) {
                    log(show(sbn, item) ? "shown from the notification list" : "not shown: the island could not open");
                }
            }
        }
    }

    private boolean alreadyHadCard(StatusBarNotification sbn) {
        return sameEvent(peeked.get(sbn.getKey()), sbn);
    }

    private boolean isRepeat(String code) {
        return code != null && code.equals(lastOtpCode) && SystemClock.uptimeMillis() - lastOtpAt < OTP_REPEAT_WINDOW_MS;
    }

    /** Only remove the system banner after a queued alert is actually visible. */
    void onQueuedPresented(IslandItem item) {
        if (item.sourceKey != null) HeadsUpHooks.dismissStock(item.sourceKey);
        if (item.otpCode != null) {
            lastOtpCode = item.otpCode;
            lastOtpAt = SystemClock.uptimeMillis();
            if (ModuleSettings.otpAutoCopy()) sources.peeks.copy(item.otpCode);
        }
    }

    /** @return whether the island really shows the card; only then is it remembered (and a code copied). */
    private boolean show(StatusBarNotification sbn, IslandItem item) {
        return show(sbn, item, 0);
    }

    private boolean show(StatusBarNotification sbn, IslandItem item, long version) {
        if (!placement.canPresent() || !allowed(sbn) || !enabled(item) || leftNative(sbn)) return false;
        long millis = item.peekMillis > 0 ? item.peekMillis : DEFAULT_PEEK_MS;
        IslandItem showing = sources.peeks.showing();
        if (showing != null && !showing.key.equals(item.key)) return false;
        if (version > 0) activeVersion = version;
        else if (!sameEvent(activeEvent, sbn)) activeVersion = ++nextVersion;
        activeEvent = sbn;
        activeItem = item;
        sources.peeks.show(item, millis);
        refreshNow.run();
        if (!placement.view().peek(item, millis)) {
            sources.peeks.dismiss(item);
            refreshNow.run();
            return false;
        }
        if (peeked.size() > MAX_PEEKED) {
            peeked.clear();
        }
        peeked.put(sbn.getKey(), sbn);
        dev.android.dynamicisland.xposed.Diagnostics.fact("heads-up presentation", "confirmed; local version " + activeVersion);
        final String key = sbn.getKey();
        placement.view().postDelayed(() -> {
            if (activeItem == item && owns(key)) HeadsUpHooks.dismissStock(key);
        }, STOCK_DISMISS_DELAY_MS);
        if (item.otpCode != null) {
            lastOtpCode = item.otpCode;
            lastOtpAt = SystemClock.uptimeMillis();
            if (ModuleSettings.otpAutoCopy()) {
                sources.peeks.copy(item.otpCode);
            }
        }
        return true;
    }

    private void log(String what) {
        if (otpLogs < MAX_OTP_LOGS) {
            otpLogs++;
            XLog.i("verification code card " + what);
        }
    }
}
