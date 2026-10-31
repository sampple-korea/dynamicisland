package dev.android.dynamicisland.island.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.graphics.BatteryIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import java.util.List;
import java.util.Locale;

/**
 * A charging card that stays for the whole charging session, in the spirit of Battery Guru's charging
 * notification: live current, power and battery temperature, plus what the session has gained with the
 * screen on and with it off (time, battery percent and mAh).
 *
 * <p>Unlike {@link EventSource}'s charging popup — which lives for a few seconds — this item is offered
 * for as long as a charger is attached and disappears only when it is unplugged.
 *
 * <p>Values come from what the phone reports, nothing is invented:
 * <ul>
 *   <li>current: {@code BatteryManager.BATTERY_PROPERTY_CURRENT_NOW} in microamperes (positive = into
 *       the battery); some fuel gauges report it negative while charging, so the magnitude is used.</li>
 *   <li>voltage: {@code ACTION_BATTERY_CHANGED}'s {@code EXTRA_VOLTAGE} in millivolts.</li>
 *   <li>power: voltage × current — measured into the battery, so it reads lower than the charger's
 *       rating.</li>
 *   <li>temperature: {@code EXTRA_TEMPERATURE} in tenths of a degree.</li>
 *   <li>mAh: {@code BATTERY_PROPERTY_CHARGE_COUNTER} in microampere-hours; the session gain is the
 *       accumulated positive delta, split by screen state.</li>
 * </ul>
 */
public final class ChargingSource {

    private static final long TICK_MS = 1000L;
    private static final int GREEN = 0xFF30D158;

    /** True while a charger is attached; also used by the event source to keep its popup quiet. */
    private static volatile boolean plugged;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;

    // ---- live readings ---------------------------------------------------------------------
    private int level = -1;          // 0..100
    private int voltageMv;
    private int tempTenths;          // tenths of a degree C
    private int currentUa;           // microamperes
    private int plugType;            // BatteryManager.BATTERY_PLUGGED_*
    private long counterUah = -1;    // charge counter, microampere-hours; -1 = not reported

    // ---- session ----------------------------------------------------------------------------
    private long sessionStart;       // elapsedRealtime when the charger was attached
    private int startLevel = -1;
    private long screenOnMs, screenOffMs;
    private double mahOn, mahOff;    // accumulated charge gain per screen state (mAh)
    private int pctOn, pctOff;       // battery percent gained per screen state
    private int lastLevel = -1;      // previous reading, for the per-state percent gain
    private boolean screenOn = true;
    private long lastTick;           // elapsedRealtime of the previous accumulation
    private boolean ticking;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!plugged) {
                ticking = false;
                return;
            }
            accumulate();
            onChange.run();
            main.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver battery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            read(i);
            onChange.run();
        }
    };

    private final BroadcastReceiver plug = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // ACTION_BATTERY_CHANGED follows with the new state; this only makes the card appear at once
            onChange.run();
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            accumulate(); // close the previous stretch before switching state
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            lastTick = SystemClock.elapsedRealtime();
            onChange.run();
        }
    };

    public ChargingSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
    }

    /** True when this source owns the charging card, so the event source does not also pop up. */
    public static boolean handlesCharging() {
        return plugged && ModuleSettings.chargingInfo();
    }

    public void start() {
        try {
            IntentFilter b = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent s = ctx.registerReceiver(battery, b);
            if (s != null) {
                read(s); // the sticky broadcast: the state right now
            }
            IntentFilter p = new IntentFilter();
            p.addAction(Intent.ACTION_POWER_CONNECTED);
            p.addAction(Intent.ACTION_POWER_DISCONNECTED);
            ctx.registerReceiver(plug, p);
            IntentFilter sc = new IntentFilter();
            sc.addAction(Intent.ACTION_SCREEN_ON);
            sc.addAction(Intent.ACTION_SCREEN_OFF);
            ctx.registerReceiver(screen, sc);
            XLog.i("charging info: plugged=" + plugged + " level=" + level);
        } catch (Throwable t) {
            XLog.e("charging info not registered", t);
        }
    }

    /** Reads the battery broadcast and starts or ends a session when the plug state changes. */
    private void read(Intent i) {
        if (i == null) {
            return;
        }
        int scale = Math.max(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int lvl = Math.round(100f * i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) / scale);
        // EXTRA_VOLTAGE is millivolts on most builds but microvolts on some Samsung ones
        // (4190000 instead of 4190); voltsFrom() picks whichever lands in a plausible range.
        int v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int pl = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);

        boolean was = plugged;
        plugged = pl != 0;
        plugType = pl;
        int previous = level;
        level = lvl;
        voltageMv = v;
        tempTenths = t;

        // percent gained, attributed to the screen state it happened in
        if (plugged && previous >= 0 && lvl > previous) {
            int gain = lvl - previous;
            if (screenOn) {
                pctOn += gain;
            } else {
                pctOff += gain;
            }
        }
        lastLevel = lvl;

        readCurrent();

        if (plugged && !was) {
            // a new charging session
            sessionStart = SystemClock.elapsedRealtime();
            startLevel = lvl;
            screenOnMs = screenOffMs = 0;
            mahOn = mahOff = 0;
            pctOn = pctOff = 0;
            screenOn = isScreenOn();
            lastTick = sessionStart;
            startTicking();
        } else if (!plugged && was) {
            ticking = false;
            main.removeCallbacks(tick);
        }
        if (plugged) {
            startTicking();
        }
    }

    /** {@code BATTERY_PROPERTY_CURRENT_NOW} and the charge counter, when the fuel gauge has them. */
    private void readCurrent() {
        try {
            BatteryManager bm = ctx.getSystemService(BatteryManager.class);
            if (bm == null) {
                return;
            }
            int ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            if (ua != Integer.MIN_VALUE) {
                currentUa = ua;
            }
            long uah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (uah != Long.MIN_VALUE && uah > 0) {
                counterUah = uah;
            }
        } catch (Throwable ignore) {
            // some fuel gauges do not answer these properties
        }
    }

    private boolean isScreenOn() {
        try {
            android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Battery voltage in volts, from whatever unit the build reports: volts, millivolts or microvolts.
     * A phone battery sits between about 3.0 V and 4.5 V, so the first unit that lands in that window
     * is the right one - 4190 (mV) and 4190000 (uV) both come out as 4.19 V.
     */
    private static float voltsFrom(int raw) {
        float v = raw;
        for (int i = 0; i < 3; i++) {
            if (v >= 2.0f && v <= 6.5f) {
                return v;
            }
            v /= 1000f;
        }
        return 0f; // implausible -> treat as unknown
    }

    private void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        lastTick = SystemClock.elapsedRealtime();
        main.postDelayed(tick, TICK_MS);
    }

    /** Adds the time since the last call to the current screen state, and the charge gained with it. */
    /**
     * Adds the time since the last tick to the current screen state, and the charge that went in
     * during it.
     *
     * <p>The charge is integrated from the live current (mA x hours) rather than taken from jumps of
     * the charge counter: that counter updates in coarse chunks on most fuel gauges, so a 50 mAh jump
     * would land entirely on whichever screen state happened to be active during the one second the
     * jump arrived in - which showed up as "3% (0 mAh)" with the screen on and "50 mAh" on a single
     * second with it off. Integrating keeps the mAh consistent with the time split.
     */
    private void accumulate() {
        long now = SystemClock.elapsedRealtime();
        long dt = lastTick == 0 ? 0 : now - lastTick;
        lastTick = now;
        if (!plugged) {
            return;
        }
        readCurrent(); // fresh current for this interval
        if (dt > 0 && dt < 60_000L) {
            double mah = (Math.abs(currentUa) / 1000.0) * (dt / 3_600_000.0);
            if (screenOn) {
                screenOnMs += dt;
                mahOn += mah;
            } else {
                screenOffMs += dt;
                mahOff += mah;
            }
        }
    }

    /** Adds the card while a charger is attached. It has no expiry, so it stays until unplugged. */
    public void collect(List<IslandItem> out) {
        if (!plugged || !ModuleSettings.chargingInfo()) {
            return;
        }
        out.add(card());
    }

    private IslandItem card() {
        IslandItem it = new IslandItem("charging:info");
        it.kind = IslandItem.Kind.EVENT;
        it.iconIsImage = false;
        it.pillColor = 0;
        it.title = t("충전 중", "Charging");
        // no pictogram: with icon == null the leading slot draws a dot in the item's accent colour,
        // which is this card's green - the compact pill and the card both show it
        it.icon = null;                 // compact pill: the accent dot
        it.cardIcon = BatteryIcon.load(ctx, level < 0 ? 100 : level, true); // the module's pill vectors
        it.accent = GREEN;
        it.description = stats();
        it.trailing = IslandItem.Trailing.TEXT;
        // the compact pill shows the live wattage instead of the battery level
        it.trailingText = wattsText();
        it.postTime = sessionStart;
        return it;
    }

    /**
     * The compact pill's text: the live charging power, e.g. "6.4 W". Falls back to the battery level
     * when the phone does not report a usable current or voltage.
     */
    private String wattsText() {
        int ma = Math.abs(currentUa) / 1000;
        float volts = voltsFrom(voltageMv);
        float watts = volts * (ma / 1000f);
        if (watts > 0.05f && watts < 300f) {
            // Whole watts only: the compact pill has room for about as much as the old "79%" did,
            // and "14.6 W" did not fit there. The card's first line still shows the exact figure.
            return Math.round(watts) + "W";
        }
        return level < 0 ? null : level + "%";
    }

    /** Three lines, as in the reference: live values, then the screen-on and screen-off gains. */
    private CharSequence stats() {
        int ma = Math.abs(currentUa) / 1000;
        float volts = voltsFrom(voltageMv);
        float watts = volts * (ma / 1000f);
        float temp = tempTenths / 10f;
        StringBuilder sb = new StringBuilder();
        sb.append(t("지금: ", "Now: ")).append(ma).append(" mA");
        // a phone charges at well under 300 W; anything outside that is a bad reading, not a value
        if (watts > 0.05f && watts < 300f) {
            sb.append(" (").append(String.format(Locale.US, "%.1f", watts)).append(" W)");
        }
        sb.append(" • ").append(String.format(Locale.US, "%.1f", temp)).append("°");
        sb.append('\n').append(line(t("화면 켜짐: ", "Screen On: "), screenOnMs, mahOn, pctOn));
        sb.append('\n').append(line(t("화면 꺼짐: ", "Screen Off: "), screenOffMs, mahOff, pctOff));
        return sb.toString();
    }

    /** {@code label + 1m 4s • 2% (34 mAh)} */
    private String line(String label, long ms, double mah, int pct) {
        return label + duration(ms) + " • " + pct + "% (" + Math.round(mah) + " mAh)";
    }

    private static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) {
            return s + "s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + "m " + (s % 60) + "s";
        }
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private String t(String ko, String en) {
        Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        return "ko".equals(locale.getLanguage()) ? ko : en;
    }

    /** For tests: pretend the charger state and readings. */
    void configure(boolean isPlugged, int lvl, int ma, int mv, int tempC) {
        plugged = isPlugged;
        level = lvl;
        currentUa = ma * 1000;
        voltageMv = mv;
        tempTenths = tempC * 10;
        if (isPlugged && sessionStart == 0) {
            sessionStart = SystemClock.elapsedRealtime();
            startLevel = lvl;
        }
    }
}
