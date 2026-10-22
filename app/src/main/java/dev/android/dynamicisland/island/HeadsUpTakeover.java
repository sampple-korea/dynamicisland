package dev.android.dynamicisland.island;

import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.detect.OtpDetector;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.source.PeekSource;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
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
    /** A verification code is "fresh" (worth a card) for this long after its notification was posted. */
    private static final long OTP_FRESH_MS = 20000;
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
    private final Set<String> otpChecked = new HashSet<>();
    private int otpLogs;
    private String lastOtpCode;
    private long lastOtpAt;

    HeadsUpTakeover(IslandSources sources, IslandPlacement placement, Runnable refreshNow) {
        this.sources = sources;
        this.placement = placement;
        this.refreshNow = refreshNow;
    }

    /** @return true when the island took the banner (the stock one must stay away) */
    boolean take(StatusBarNotification sbn) {
        try {
            IslandView view = placement.view();
            if (sbn == null || !placement.ready()) {
                return false;
            }
            if (!ModuleSettings.appAllowed(sbn.getPackageName())) {
                return false; // the user keeps this app out of the island: its banner stays stock
            }
            if (isOnIslandNow(sbn)) {
                return true; // already on the island: the banner is asked about again on updates
            }
            if (view.isSuppressedNow() || !PeekSource.eligible(sbn)
                    || sbn.getPackageName().equals(sources.foregroundPackage())) {
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
            if (!ModuleSettings.headsUpIsland() || view.isExpanded()) {
                return false;
            }
            IslandItem message = sources.peeks.buildMessage(sbn);
            return message != null && show(sbn, message);
        } catch (Throwable t) {
            XLog.once("IslandController.peekHeadsUp", t);
            return false;
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
            if (!otpChecked.add(sbn.getKey() + "@" + sbn.getPostTime()) || !PeekSource.eligible(sbn)) {
                continue;
            }
            OtpDetector.Match match = OtpDetector.detect(PeekSource.otpText(sbn.getNotification()));
            // a messages app re-posting the same text (marked read, another device synced) is not a new code
            if (match == null || isRepeat(match.code)) {
                continue;
            }
            if (sbn.getPackageName().equals(sources.foregroundPackage())) {
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
        Long seen = peeked.get(sbn.getKey());
        return seen != null && seen == sbn.getPostTime();
    }

    /** This very posting is the card on the island right now. */
    private boolean isOnIslandNow(StatusBarNotification sbn) {
        IslandItem showing = sources.peeks.showing();
        return alreadyHadCard(sbn) && showing != null && showing.key.equals("peek:" + sbn.getKey());
    }

    private boolean isRepeat(String code) {
        return code != null && code.equals(lastOtpCode) && SystemClock.uptimeMillis() - lastOtpAt < OTP_REPEAT_WINDOW_MS;
    }

    /** @return whether the island really shows the card; only then is it remembered (and a code copied). */
    private boolean show(StatusBarNotification sbn, IslandItem item) {
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
