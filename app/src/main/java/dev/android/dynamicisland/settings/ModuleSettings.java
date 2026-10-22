package dev.android.dynamicisland.settings;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import de.robv.android.xposed.XSharedPreferences;
import dev.android.dynamicisland.island.rules.RuleConfig;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.XLog;
import java.util.Set;

/**
 * User options chosen in {@link SettingsActivity}, read inside System UI through
 * {@link XSharedPreferences}. Every getter falls back to its default when the preferences are not
 * readable (module scope not granted yet, first boot, ...), so nothing here can break the island.
 */
public final class ModuleSettings {
    public static final String PACKAGE = "dev.android.dynamicisland";
    public static final String FILE = "settings";

    public static final String KEY_PAUSED_TIMEOUT_S = "paused_timeout_s";
    public static final String KEY_AUTO_COLLAPSE_S = "auto_collapse_s";
    public static final String KEY_CARD_HEADER = "card_header";
    public static final String KEY_VISUALIZER = "visualizer";
    public static final String KEY_EVENT_CHARGING = "event_charging";
    public static final String KEY_EVENT_RINGER = "event_ringer";
    public static final String KEY_EVENT_BLUETOOTH = "event_bluetooth";
    public static final String KEY_HAPTICS = "haptics";
    /** Plays the animations of version 0.1.3 and before instead of the smoothed ones. */
    public static final String KEY_LEGACY_ANIMATION = "legacy_animation";
    /** Where the island sits: {@link #ANCHOR_AUTO} (on the camera hole), the middle, the left or the right of the status bar. */
    public static final String KEY_PILL_ANCHOR = "pill_anchor";
    public static final String KEY_PILL_X_DP = "pill_x_dp";
    public static final String KEY_PILL_Y_DP = "pill_y_dp";
    /** Wall-clock time until which the position page is open; a slower way (the settings file) to ask for the sample. */
    public static final String KEY_PILL_PREVIEW_UNTIL = "pill_preview_until";
    /** The settings app tells System UI about a position it is trying out, so the real island moves at once. */
    public static final String ACTION_PILL_PREVIEW = "dev.android.dynamicisland.action.PILL_PREVIEW";
    public static final String EXTRA_ANCHOR = "anchor";
    public static final String EXTRA_X_DP = "x_dp";
    public static final String EXTRA_Y_DP = "y_dp";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_HOLE_MODE = "hole_mode";
    public static final String EXTRA_HOLE_D_DP = "hole_d_dp";
    public static final String EXTRA_HOLE_X_DP = "hole_x_dp";
    public static final String EXTRA_HOLE_Y_DP = "hole_y_dp";
    /** A tried-out position counts for this long without another message (the page sends a heartbeat). */
    private static final long PREVIEW_MS = 6000;
    /** The camera hole the island grows from: found from the display cutout, set by hand, or none at all. */
    public static final String KEY_HOLE_MODE = "hole_mode";
    public static final String KEY_HOLE_D_DP = "hole_d_dp";
    public static final String KEY_HOLE_X_DP = "hole_x_dp";
    public static final String KEY_HOLE_Y_DP = "hole_y_dp";
    public static final int HOLE_AUTO = 0;
    public static final int HOLE_MANUAL = 1;
    public static final int HOLE_NONE = 2;
    public static final int HOLE_D_MIN_DP = 6;
    public static final int HOLE_D_MAX_DP = 60;
    public static final int HOLE_D_DEFAULT_DP = 22;
    public static final int HOLE_X_MAX_DP = 200;
    public static final int HOLE_Y_MIN_DP = -24;
    public static final int HOLE_Y_MAX_DP = 48;
    public static final int ANCHOR_AUTO = 0;
    public static final int ANCHOR_CENTER = 1;
    public static final int ANCHOR_LEFT = 2;
    public static final int ANCHOR_RIGHT = 3;
    public static final int PILL_X_MAX_DP = 160;
    public static final int PILL_Y_MIN_DP = -16;
    public static final int PILL_Y_MAX_DP = 64;
    public static final String KEY_HEADS_UP_ISLAND = "heads_up_island";
    public static final String KEY_PROGRESS_NOTIFICATIONS = "progress_notifications";
    public static final String KEY_HIDE_PROGRESS_ORIGINALS = "hide_progress_originals";
    /** Cards for staged processes (delivery, taxi, route, connection) found by the rules. */
    public static final String KEY_SMART_RULES = "smart_rules";
    /** The user's own rule file. */
    public static final String KEY_RULES_CUSTOM = "rules_custom";
    /** Ids of the smart rules switched off. */
    public static final String KEY_RULES_DISABLED = "rules_disabled";
    public static final String KEY_EVENT_TOGGLES = "event_toggles";
    public static final String KEY_PRIVACY = "privacy_indicator";
    public static final String KEY_PRIVACY_LOCATION = "privacy_location";
    public static final String KEY_OTP_CARD = "otp_card";
    public static final String KEY_OTP_AUTO_COPY = "otp_auto_copy";
    public static final String KEY_THIRD_PARTY_CALLS = "third_party_calls";
    /** true = only the listed apps are shown, false (default) = every app except the listed ones. */
    public static final String KEY_APPS_ONLY_LISTED = "apps_only_listed";
    /** Package names of the listed apps. */
    public static final String KEY_APPS_LISTED = "apps_listed";

    public static final int DEF_PAUSED_TIMEOUT_S = 60;
    public static final int DEF_AUTO_COLLAPSE_S = 6;

    private static final long RELOAD_INTERVAL_MS = 4000;
    /** Floors for the timeouts, so a bad stored value cannot make the island flicker away at once. */
    private static final int MIN_PAUSED_TIMEOUT_S = 5;
    private static final int MIN_AUTO_COLLAPSE_S = 2;
    private static final long MS_PER_SECOND = 1000L;

    // Values are cached and refreshed on a background thread: reading the preferences goes through
    // file / binder access that must never block (or throw into) System UI's main thread.
    private static volatile int pausedTimeoutS = DEF_PAUSED_TIMEOUT_S;
    private static volatile int autoCollapseS = DEF_AUTO_COLLAPSE_S;
    private static volatile boolean cardHeader = true;
    private static volatile boolean visualizer = true;
    private static volatile boolean eventCharging = true;
    private static volatile boolean eventRinger = true;
    private static volatile boolean eventBluetooth = true;
    private static volatile boolean haptics = true;
    private static volatile boolean legacyAnimation;
    private static volatile boolean headsUpIsland = true;
    private static volatile boolean progressNotifications = true;
    private static volatile boolean hideProgressOriginals = true;
    private static volatile boolean smartRules = true;
    private static volatile int pillAnchor = ANCHOR_AUTO;
    private static volatile int pillXDp;
    private static volatile int pillYDp;
    /** Told (on the reload thread) when the island's position settings changed. */
    private static volatile Runnable onPlacementChanged;
    private static volatile int holeMode = HOLE_AUTO;
    private static volatile int holeDDp = HOLE_D_DEFAULT_DP;
    private static volatile int holeXDp;
    private static volatile int holeYDp;
    private static volatile long previewUntil;
    private static volatile long previewWallUntil;
    private static volatile RuleConfig rulesConfig = RuleConfig.DEFAULT;
    /** Told (on the reload thread) when the user's rules changed, so cards already showing can follow. */
    private static volatile Runnable onRulesChanged;
    private static volatile boolean eventToggles = true;
    private static volatile boolean privacyIndicator = true;
    private static volatile boolean privacyLocation = false;
    private static volatile boolean otpCard = true;
    private static volatile boolean otpAutoCopy = false;
    private static volatile boolean thirdPartyCalls = true;
    private static volatile AppFilter appFilter = AppFilter.OFF;

    private static boolean started;
    private static XSharedPreferences prefs;
    private static String lastLogged;

    private ModuleSettings() {
    }

    public static void setOnPlacementChanged(Runnable listener) {
        onPlacementChanged = listener;
    }

    /**
     * Takes a position the settings app is trying out. While the preview lasts the island shows a sample item, and
     * the saved settings do not override the position (they are written when the finger lets go).
     */
    public static void applyPlacementPreview(int anchor, int xDp, int yDp, int mode, int holeD, int holeX, int holeY, boolean active) {
        holeMode = Math.max(HOLE_AUTO, Math.min(HOLE_NONE, mode));
        holeDDp = Math.max(HOLE_D_MIN_DP, Math.min(HOLE_D_MAX_DP, holeD));
        holeXDp = Math.max(-HOLE_X_MAX_DP, Math.min(HOLE_X_MAX_DP, holeX));
        holeYDp = Math.max(HOLE_Y_MIN_DP, Math.min(HOLE_Y_MAX_DP, holeY));
        pillAnchor = Math.max(ANCHOR_AUTO, Math.min(ANCHOR_RIGHT, anchor));
        pillXDp = Math.max(-PILL_X_MAX_DP, Math.min(PILL_X_MAX_DP, xDp));
        pillYDp = Math.max(PILL_Y_MIN_DP, Math.min(PILL_Y_MAX_DP, yDp));
        previewUntil = active ? android.os.SystemClock.uptimeMillis() + PREVIEW_MS : 0L;
        Runnable listener = onPlacementChanged;
        if (listener != null) {
            listener.run();
        }
    }

    /** True while the settings app is moving the island: it shows a sample item so there is something to move. */
    public static boolean placementPreviewActive() {
        return android.os.SystemClock.uptimeMillis() < previewUntil || System.currentTimeMillis() < previewWallUntil;
    }

    public static int holeMode() {
        ensureStarted();
        return holeMode;
    }

    /** Diameter of the camera hole in dp when it is set by hand. */
    public static int holeDiameterDp() {
        ensureStarted();
        return holeDDp;
    }

    /** Sideways shift of the hole from the middle of the screen, in dp. */
    public static int holeXDp() {
        ensureStarted();
        return holeXDp;
    }

    /** Vertical shift of the hole from the middle of the status bar, in dp. */
    public static int holeYDp() {
        ensureStarted();
        return holeYDp;
    }

    public static int pillAnchor() {
        ensureStarted();
        return pillAnchor;
    }

    /** Horizontal shift of the island in dp (positive = right). */
    public static int pillXDp() {
        ensureStarted();
        return pillXDp;
    }

    /** Vertical shift of the island in dp (positive = down). */
    public static int pillYDp() {
        ensureStarted();
        return pillYDp;
    }

    public static void setOnRulesChanged(Runnable listener) {
        onRulesChanged = listener;
    }

    /** Starts the background reload loop on first use; later calls do nothing. */
    public static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        refresh(); // the first values are in place before the first getter returns
        try {
            HandlerThread thread = new HandlerThread("DynamicIslandPrefs", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            final Handler handler = new Handler(thread.getLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    refresh();
                    handler.postDelayed(this, RELOAD_INTERVAL_MS);
                }
            });
        } catch (Throwable ignored) {
            // keep the defaults
        }
    }

    /** Re-reads the preferences file when it changed; runs on the reload thread only. */
    private static void refresh() {
        try {
            if (prefs == null) {
                prefs = new XSharedPreferences(PACKAGE, FILE);
                prefs.makeWorldReadable();
                XLog.event("settings file " + prefs.getFile() + " readable=" + prefs.getFile().canRead());
            } else if (prefs.hasFileChanged()) {
                prefs.reload();
            } else {
                return;
            }
            pausedTimeoutS = prefs.getInt(KEY_PAUSED_TIMEOUT_S, DEF_PAUSED_TIMEOUT_S);
            autoCollapseS = prefs.getInt(KEY_AUTO_COLLAPSE_S, DEF_AUTO_COLLAPSE_S);
            cardHeader = prefs.getBoolean(KEY_CARD_HEADER, true);
            visualizer = prefs.getBoolean(KEY_VISUALIZER, true);
            eventCharging = prefs.getBoolean(KEY_EVENT_CHARGING, true);
            eventRinger = prefs.getBoolean(KEY_EVENT_RINGER, true);
            eventBluetooth = prefs.getBoolean(KEY_EVENT_BLUETOOTH, true);
            haptics = prefs.getBoolean(KEY_HAPTICS, true);
            legacyAnimation = prefs.getBoolean(KEY_LEGACY_ANIMATION, false);
            headsUpIsland = prefs.getBoolean(KEY_HEADS_UP_ISLAND, true);
            progressNotifications = prefs.getBoolean(KEY_PROGRESS_NOTIFICATIONS, true);
            hideProgressOriginals = prefs.getBoolean(KEY_HIDE_PROGRESS_ORIGINALS, true);
            smartRules = prefs.getBoolean(KEY_SMART_RULES, true);
            int holeBefore = holeMode * 1_000_000 + holeDDp * 10_000 + holeXDp * 100 + holeYDp;
            int anchorBefore = pillAnchor;
            int xBefore = pillXDp;
            int yBefore = pillYDp;
            boolean previewBefore = placementPreviewActive();
            previewWallUntil = prefs.getLong(KEY_PILL_PREVIEW_UNTIL, 0L);
            if (!placementPreviewActive()) {
                pillAnchor = Math.max(ANCHOR_AUTO, Math.min(ANCHOR_RIGHT, prefs.getInt(KEY_PILL_ANCHOR, ANCHOR_AUTO)));
                pillXDp = Math.max(-PILL_X_MAX_DP, Math.min(PILL_X_MAX_DP, prefs.getInt(KEY_PILL_X_DP, 0)));
                pillYDp = Math.max(PILL_Y_MIN_DP, Math.min(PILL_Y_MAX_DP, prefs.getInt(KEY_PILL_Y_DP, 0)));
                holeMode = Math.max(HOLE_AUTO, Math.min(HOLE_NONE, prefs.getInt(KEY_HOLE_MODE, HOLE_AUTO)));
                holeDDp = Math.max(HOLE_D_MIN_DP, Math.min(HOLE_D_MAX_DP, prefs.getInt(KEY_HOLE_D_DP, HOLE_D_DEFAULT_DP)));
                holeXDp = Math.max(-HOLE_X_MAX_DP, Math.min(HOLE_X_MAX_DP, prefs.getInt(KEY_HOLE_X_DP, 0)));
                holeYDp = Math.max(HOLE_Y_MIN_DP, Math.min(HOLE_Y_MAX_DP, prefs.getInt(KEY_HOLE_Y_DP, 0)));
            }
            Runnable placementListener = onPlacementChanged;
            if (placementListener != null && (anchorBefore != pillAnchor || xBefore != pillXDp || yBefore != pillYDp
                    || previewBefore != placementPreviewActive()
                    || holeBefore != holeMode * 1_000_000 + holeDDp * 10_000 + holeXDp * 100 + holeYDp)) {
                placementListener.run();
            }
            RuleConfig before = rulesConfig;
            rulesConfig = readRulesConfig(prefs);
            Runnable listener = onRulesChanged;
            if (listener != null && rulesConfig.revision != before.revision) {
                listener.run();
            }
            eventToggles = prefs.getBoolean(KEY_EVENT_TOGGLES, true);
            privacyIndicator = prefs.getBoolean(KEY_PRIVACY, true);
            privacyLocation = prefs.getBoolean(KEY_PRIVACY_LOCATION, false);
            otpCard = prefs.getBoolean(KEY_OTP_CARD, true);
            otpAutoCopy = prefs.getBoolean(KEY_OTP_AUTO_COPY, false);
            thirdPartyCalls = prefs.getBoolean(KEY_THIRD_PARTY_CALLS, true);
            Set<String> listed = prefs.getStringSet(KEY_APPS_LISTED, null);
            appFilter = new AppFilter(prefs.getBoolean(KEY_APPS_ONLY_LISTED, false),
                    listed == null ? null : new java.util.HashSet<>(listed));
            logChanges();
        } catch (Throwable t) {
            // unreadable preferences: keep the last known / default values
            XLog.once("ModuleSettings.refresh", t);
        }
    }

    /** One line in the event log whenever the values the module runs on differ from the last read. */
    private static void logChanges() {
        String now = "haptics=" + haptics + " legacyAnimation=" + legacyAnimation + " visualizer=" + visualizer + " cardHeader=" + cardHeader
                + " headsUpIsland=" + headsUpIsland + " progress=" + progressNotifications + " hideOriginals=" + hideProgressOriginals
                + " smartRules=" + smartRules + " otp=" + otpCard + "/" + otpAutoCopy + " calls3p=" + thirdPartyCalls
                + " privacy=" + privacyIndicator + "/" + privacyLocation + " events=" + eventCharging + "/" + eventRinger + "/" + eventBluetooth
                + " pill=" + pillAnchor + "/" + pillXDp + "/" + pillYDp + " hole=" + holeMode + "/" + holeDDp + "/" + holeXDp + "/" + holeYDp + " pausedS=" + pausedTimeoutS + " collapseS=" + autoCollapseS + " rules=" + rulesConfig.revision
                + " appFilter=" + (appFilter.onlyListed() ? "only " : "except ") + appFilter.size();
        Diagnostics.fact("settings file", prefs.getFile() + " readable=" + prefs.getFile().canRead() + " keys=" + prefs.getAll().size());
        if (!now.equals(lastLogged)) {
            lastLogged = now;
            XLog.event("settings: " + now);
        }
    }

    private static RuleConfig readRulesConfig(XSharedPreferences source) {
        String custom = source.getString(KEY_RULES_CUSTOM, null);
        Set<String> off = source.getStringSet(KEY_RULES_DISABLED, null);
        RuleConfig next = new RuleConfig(custom == null || custom.isEmpty() ? null : custom,
                off == null ? null : new java.util.HashSet<>(off));
        return next.revision == rulesConfig.revision ? rulesConfig : next;
    }

    /** Staged cards from the rules (delivery, taxi, route, device connection). */
    public static boolean smartRules() {
        ensureStarted();
        return smartRules;
    }

    /** The rule file the user wrote, if any. */
    public static RuleConfig rulesConfig() {
        ensureStarted();
        return rulesConfig;
    }

    /** How long a paused player stays on the island. */
    public static long pausedTimeoutMs() {
        ensureStarted();
        return Math.max(MIN_PAUSED_TIMEOUT_S, pausedTimeoutS) * MS_PER_SECOND;
    }

    /** How long an expanded island waits before folding back. */
    public static long autoCollapseMs() {
        ensureStarted();
        return Math.max(MIN_AUTO_COLLAPSE_S, autoCollapseS) * MS_PER_SECOND;
    }

    /** App name + time line at the top of live cards. */
    public static boolean cardHeader() {
        ensureStarted();
        return cardHeader;
    }

    /** Drive the waveform from the audio output instead of random bars. */
    public static boolean visualizer() {
        ensureStarted();
        return visualizer;
    }

    /** Charging / low battery popups. */
    public static boolean eventCharging() {
        ensureStarted();
        return eventCharging;
    }

    /** Silent / vibrate / ring popup. */
    public static boolean eventRinger() {
        ensureStarted();
        return eventRinger;
    }

    /** Bluetooth headphones connected popup. */
    public static boolean eventBluetooth() {
        ensureStarted();
        return eventBluetooth;
    }

    /** Messages and other banner notifications as island cards instead of the stock heads-up. */
    public static boolean headsUpIsland() {
        ensureStarted();
        return headsUpIsland;
    }

    /** Drop the shade row of a progress notification while the island shows its card. */
    public static boolean hideProgressOriginals() {
        ensureStarted();
        return hideProgressOriginals;
    }

    /** Downloads, uploads and other plain progress notifications as island cards. */
    public static boolean progressNotifications() {
        ensureStarted();
        return progressNotifications;
    }

    /** Verification-code card for one-time passwords found in notifications. */
    public static boolean otpCard() {
        ensureStarted();
        return otpCard;
    }

    /** Copy a detected verification code to the clipboard without a tap. */
    public static boolean otpAutoCopy() {
        ensureStarted();
        return otpAutoCopy;
    }

    /** False for an app the user blocked (or left out of the allow list); its cards and banners stay stock. */
    public static boolean appAllowed(String packageName) {
        ensureStarted();
        return appFilter.allows(packageName);
    }

    /** Calls from third-party calling apps shown on the island, not only the phone app's. */
    public static boolean thirdPartyCalls() {
        ensureStarted();
        return thirdPartyCalls;
    }

    /** Do Not Disturb / airplane / power saving / flashlight / hotspot / auto-rotate feedback. */
    public static boolean eventToggles() {
        ensureStarted();
        return eventToggles;
    }

    /** Camera / microphone in use island. */
    public static boolean privacyIndicator() {
        ensureStarted();
        return privacyIndicator;
    }

    /** Also announce location use (noisy: navigation and weather apps use it all the time). */
    public static boolean privacyLocation() {
        ensureStarted();
        return privacyLocation;
    }

    /** The animations of 0.1.3 and before instead of the smoothed ones (default off). */
    public static boolean legacyAnimation() {
        ensureStarted();
        return legacyAnimation;
    }

    /** Tick when the island expands / collapses and click on button presses. */
    public static boolean haptics() {
        ensureStarted();
        return haptics;
    }
}
