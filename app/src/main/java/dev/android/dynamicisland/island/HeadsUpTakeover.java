package dev.android.dynamicisland.island;

import android.os.SystemClock;
import android.app.Notification;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.detect.OtpDetector;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.source.PeekSource;
import dev.android.dynamicisland.island.view.IslandView;
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
    private final Map<String, Long> peeked = new HashMap<>();
    /** When each notification key was last put on the island, and the text it was shown with. */
    private final Map<String, Long> shownAt = new HashMap<>();
    private final Map<String, String> shownText = new HashMap<>();
    /**
     * An app that re-posts the same message over and over (Telegram does it on every read, sync and edit)
     * must not make the island pop the same card again: the log shows one notification taken fifteen times
     * in eight minutes. A re-post with identical text inside this window counts as already shown.
     */
    private static final long REPEAT_QUIET_MS = 180_000L;
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
        snapshot = new java.util.ArrayList<>(notifications);
    }

    boolean owns(String key) {
        IslandItem item = sources.peeks.showing();
        if (item == null || !item.key.equals("peek:" + key) || !placement.view().isShowingCard(item)) return false;
        if (!ModuleSettings.appAllowed(item.packageName)) return false;
        if (item.otpCode != null ? !ModuleSettings.otpCard() : !(ModuleSettings.headsUpIsland() || ModuleSettings.allNotifications())) return false;
        for (StatusBarNotification notification : snapshot) {
            if (notification.getKey().equals(key) && notification.getPostTime() != item.postTime) return false;
        }
        return true;
    }

    private boolean foregroundBlocked(StatusBarNotification sbn) {
        return !ModuleSettings.foregroundNotifications() && sbn.getPackageName().equals(sources.foregroundPackage());
    }

    /**
     * Notifications that arrived while the island was hidden (a fullscreen app is up, the screen is off).
     * They used to be dropped on the spot, so a message arriving in that window never appeared at all.
     */
    private final java.util.List<StatusBarNotification> pending = new java.util.ArrayList<>();
    private static final long PENDING_MS = 20_000L;
    private static final int MAX_PENDING = 12;

    private void holdForLater(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null) {
            return;
        }
        for (StatusBarNotification other : pending) {
            if (sbn.getKey().equals(other.getKey()) && sbn.getPostTime() == other.getPostTime()) {
                return;
            }
        }
        if (pending.size() >= MAX_PENDING) {
            pending.remove(0);
        }
        pending.add(sbn);
    }

    /** Called on every refresh: anything refused while the island was hidden is taken now that it is back. */
    void retryPending() {
        if (pending.isEmpty()) {
            return;
        }
        IslandView view = placement.view();
        if (view == null || view.isSuppressedNow() || !placement.ready()) {
            return;
        }
        long now = System.currentTimeMillis();
        java.util.List<StatusBarNotification> still = new java.util.ArrayList<>();
        for (StatusBarNotification sbn : new java.util.ArrayList<>(pending)) {
            if (now - sbn.getPostTime() > PENDING_MS) {
                continue;
            }
            if (!take(sbn)) {
                still.add(sbn);
            }
        }
        pending.clear();
        pending.addAll(still);
    }

    /** @return true when the island took the banner (the stock one must stay away) */
    boolean take(StatusBarNotification sbn) {
        try {
            IslandView view = placement.view();
            if (sbn == null || !placement.ready()) {
                return false;
            }
            if (!ModuleSettings.appAllowed(sbn.getPackageName())) {
                reject("banner of " + sbn.getPackageName() + ": the app is kept out of the island");
                return false; // the user keeps this app out of the island: its banner stays stock
            }
            if (isOnIslandNow(sbn) || recentlyShown(sbn)) {
                return true; // already on the island (or the same message just was): leave the banner alone
            }
            if (view.isSuppressedNow()) {
                holdForLater(sbn);
            }
            if (view.isSuppressedNow() || !PeekSource.eligible(sbn)
                    || (!ModuleSettings.experimentalRecovery()
                    && (foregroundBlocked(sbn) || !PeekSource.preferred(sbn, snapshot)))) {
                reject("banner of " + sbn.getPackageName() + ": " + (view.isSuppressedNow() ? "island hidden" : !PeekSource.eligible(sbn) ? "not an ordinary notification" : "its app is open"));
                return false;
            }
            // a code card is shown whatever the "notifications in the island" setting and even over an open card
            IslandItem code = sources.peeks.buildOtp(sbn);
            if (code != null) {
                if (isRepeat(code.otpCode)) {
                    // the same code again (re-posted, or mirrored by another app): one card is enough
                    IslandItem showing = sources.peeks.showing();
                    return showing != null && code.otpCode.equals(showing.otpCode);
                }
                boolean shown = show(sbn, code);
                log(shown ? "shown with its heads-up" : "not shown: the island could not open");
                return shown;
            }
            if (!(ModuleSettings.headsUpIsland() || ModuleSettings.allNotifications())) {
                reject("banner of " + sbn.getPackageName() + ": " + (view.isExpanded() ? "a card is open" : "the notification settings are off"));
                return false;
            }
            IslandItem message = sources.peeks.buildMessage(sbn);
            boolean taken = message != null && show(sbn, message);
            note("banner of " + sbn.getPackageName() + (taken ? " taken by the island" : " left to the stock banner: no card could be built or opened"));
            return taken;
        } catch (Throwable t) {
            XLog.once("IslandController.peekHeadsUp", t);
            return false;
        }
    }

    /**
     * The "all notifications" setting: a fresh notification that never became a banner (silent, or the system
     * did not ask) still gets a card. Looks at each posting once and shows only the newest of a burst.
     */
    void scanAll(List<StatusBarNotification> snapshot) {
        if (!ModuleSettings.allNotifications() || !placement.ready()) {
            return;
        }
        if (allChecked.size() > MAX_OTP_CHECKED) {
            allChecked.clear();
        }
        long now = System.currentTimeMillis();
        StatusBarNotification newest = null;
        for (StatusBarNotification sbn : snapshot) {
            long age = now - sbn.getPostTime();
            if (age > ALL_FRESH_MS || age < -OTP_CLOCK_SKEW_MS || !allChecked.add(sbn.getKey() + "@" + sbn.getPostTime())) {
                continue;
            }
            if (alreadyHadCard(sbn) || recentlyShown(sbn) || !PeekSource.eligible(sbn) || !ModuleSettings.appAllowed(sbn.getPackageName())
                    || (!ModuleSettings.experimentalRecovery()
                    && (foregroundBlocked(sbn) || !PeekSource.preferred(sbn, snapshot)))) {
                continue;
            }
            if (newest == null || sbn.getPostTime() >= newest.getPostTime()) {
                newest = sbn;
            }
        }
        if (newest == null) {
            return;
        }
        IslandView view = placement.view();
        if (ModuleSettings.experimentalRecovery() ? view.isSuppressedNow() : !view.canPeek()) {
            if (view.isSuppressedNow()) holdForLater(newest);
            reject("all notifications: the island is unavailable");
            return;
        }
        IslandItem item = sources.peeks.build(newest);
        if (item != null) {
            if (show(newest, item)) {
                note("card for " + newest.getPackageName() + " from the notification list (stock banner asked to leave: "
                        + HeadsUpHooks.dismissStock(newest.getKey()) + ")"); // the system's banner may have come up first
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
        if (!ModuleSettings.otpCard() || !placement.ready()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (otpChecked.size() > MAX_OTP_CHECKED) {
            otpChecked.clear();
        }
        for (StatusBarNotification sbn : snapshot) {
            long age = now - sbn.getPostTime();
            if (age > OTP_FRESH_MS || age < -OTP_CLOCK_SKEW_MS || alreadyHadCard(sbn)) {
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

    /** Title and text of a notification, for spotting an unchanged re-post. */
    private static String textOf(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) {
                return null;
            }
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            return (title == null ? "" : title.toString()) + '\u0001' + (text == null ? "" : text.toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when this very message was already on the island a moment ago, even under a new posting. */
    private boolean recentlyShown(StatusBarNotification sbn) {
        if (sbn == null || sbn.getKey() == null) {
            return false;
        }
        Long at = shownAt.get(sbn.getKey());
        if (at == null || SystemClock.uptimeMillis() - at >= REPEAT_QUIET_MS) {
            return false;
        }
        String text = textOf(sbn);
        return text != null && text.equals(shownText.get(sbn.getKey()));
    }

    private boolean alreadyHadCard(StatusBarNotification sbn) {
        Long seen = peeked.get(sbn.getKey());
        return seen != null && seen == sbn.getPostTime();
    }

    /** This very posting is the card on the island right now. */
    private boolean isOnIslandNow(StatusBarNotification sbn) {
        return alreadyHadCard(sbn) && !foregroundBlocked(sbn) && owns(sbn.getKey());
    }

    private boolean isRepeat(String code) {
        return code != null && code.equals(lastOtpCode) && SystemClock.uptimeMillis() - lastOtpAt < OTP_REPEAT_WINDOW_MS;
    }

    /** @return whether the island really shows the card; only then is it remembered (and a code copied). */
    private boolean show(StatusBarNotification sbn, IslandItem item) {
        if (!placement.view().canPeek()) return false;
        long millis = item.peekMillis > 0 ? item.peekMillis : DEFAULT_PEEK_MS;
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
        peeked.put(sbn.getKey(), sbn.getPostTime());
        if (shownAt.size() > MAX_PEEKED) {
            shownAt.clear();
            shownText.clear();
        }
        shownAt.put(sbn.getKey(), SystemClock.uptimeMillis());
        shownText.put(sbn.getKey(), textOf(sbn));
        final String key = sbn.getKey();
        placement.view().postDelayed(() -> HeadsUpHooks.dismissStock(key), STOCK_DISMISS_DELAY_MS);
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
