package dev.android.dynamicisland.island.source;

import android.content.Intent;
import android.os.BatteryManager;

/**
 * What the charger is doing to the battery, read from Samsung's extras of {@link Intent#ACTION_BATTERY_CHANGED}:
 * charging, held by battery protection, or powering the phone while the battery rests (pass-through).
 */
public final class ChargingState {
    public static final int CHARGING = 0;
    /** Battery protection holds the charge (the stock status bar's "protection working"). */
    public static final int PROTECTED = 1;
    /** Plugged in and not charging for no other reason: the charger powers the phone directly. */
    public static final int BYPASS = 2;

    /** Samsung battery event bits in {@code misc_event}, as One UI 9's System UI reads them. */
    static final int MISC_WATER = 0x1;
    static final int MISC_HICCUP = 0x20;
    static final int MISC_WIRELESS_FOD = 0x100;
    static final int MISC_TEMP_HICCUP = 0x2000;
    static final int MISC_WIRELESS_OVERHEAT = 0x8000;
    static final int MISC_WIRELESS_MISALIGN = 0x400000;
    static final int MISC_FULL_CAPACITY = 0x1000000;
    /** Any of these stops charging for a fault, which is not pass-through. */
    static final int MISC_FAULTS = MISC_WATER | MISC_HICCUP | MISC_WIRELESS_FOD | MISC_TEMP_HICCUP
            | MISC_WIRELESS_OVERHEAT | MISC_WIRELESS_MISALIGN;
    /** Swelling protection in {@code current_event}. */
    static final int CURRENT_EVENT_SWELLING = 0x30;

    private ChargingState() {
    }

    public static int of(Intent battery) {
        if (battery == null) return CHARGING;
        int state = of(battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                battery.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD),
                battery.getIntExtra("online", 1), battery.getIntExtra("protection", 0),
                battery.getIntExtra("misc_event", 0), battery.getIntExtra("current_event", 0));
        // pass-through is only told apart on Samsung's battery service (it reports these events); elsewhere a
        // paused charge has other reasons the island cannot see, so it stays an ordinary charge
        return state == BYPASS && !battery.hasExtra("misc_event") ? CHARGING : state;
    }

    /**
     * Protected follows One UI 9's own test (BatteryStateRepository: the {@code protection} extra is set, or the
     * full-capacity bit of {@code misc_event}). System UI has no pass-through flag, so that one is narrowed down:
     * plugged in, not charging, not protected, a good battery, a compatible charger and no fault reported.
     */
    static int of(int plugged, int status, int health, int online, int protection, int misc, int currentEvent) {
        if (plugged == 0) return CHARGING;
        if (protection != 0 || (misc & MISC_FULL_CAPACITY) != 0) return PROTECTED;
        if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING && health == BatteryManager.BATTERY_HEALTH_GOOD
                && online != 0 && (misc & MISC_FAULTS) == 0 && (currentEvent & CURRENT_EVENT_SWELLING) == 0) {
            return BYPASS;
        }
        return CHARGING;
    }

    /** The raw values, for the Diagnostics report. */
    static String describe(Intent battery) {
        if (battery == null) return "no battery state";
        return "plugged=" + battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                + " status=" + battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                + " health=" + battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                + " online=" + battery.getIntExtra("online", -1)
                + " protection=" + battery.getIntExtra("protection", -1)
                + " misc_event=" + (battery.hasExtra("misc_event") ? "0x" + Integer.toHexString(battery.getIntExtra("misc_event", 0)) : "absent")
                + " current_event=0x" + Integer.toHexString(battery.getIntExtra("current_event", 0))
                + " -> " + name(of(battery));
    }

    static String name(int state) {
        return state == PROTECTED ? "protected" : state == BYPASS ? "pass-through" : "charging";
    }
}
