package dev.android.dynamicisland.island.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.BatteryManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ChargingStateTest {
    private static Intent battery(int plugged, int status, int protection, int misc) {
        return new Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
                .putExtra(BatteryManager.EXTRA_STATUS, status)
                .putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD)
                .putExtra("online", 3)
                .putExtra("protection", protection)
                .putExtra("misc_event", misc);
    }

    private static final int USB = BatteryManager.BATTERY_PLUGGED_USB;
    private static final int CHARGING = BatteryManager.BATTERY_STATUS_CHARGING;
    private static final int NOT_CHARGING = BatteryManager.BATTERY_STATUS_NOT_CHARGING;

    @Test public void anOrdinaryChargeIsCharging() {
        assertEquals(ChargingState.CHARGING, ChargingState.of(battery(USB, CHARGING, 0, 0)));
        assertEquals("unplugged is never anything else", ChargingState.CHARGING, ChargingState.of(battery(0, NOT_CHARGING, 1, 0)));
        assertEquals(ChargingState.CHARGING, ChargingState.of((Intent) null));
    }

    @Test public void protectionIsTheProtectionExtraOrTheFullCapacityBitAsInOneUi() {
        assertEquals(ChargingState.PROTECTED, ChargingState.of(battery(USB, NOT_CHARGING, 1, 0)));
        assertEquals(ChargingState.PROTECTED, ChargingState.of(battery(USB, CHARGING, 2, 0)));
        assertEquals(ChargingState.PROTECTED, ChargingState.of(battery(USB, NOT_CHARGING, 0, ChargingState.MISC_FULL_CAPACITY)));
    }

    @Test public void notChargingWithNothingElseToExplainItIsPassThrough() {
        assertEquals(ChargingState.BYPASS, ChargingState.of(battery(USB, NOT_CHARGING, 0, 0)));
    }

    @Test public void withoutSamsungsBatteryEventsAPausedChargeIsNotPassThrough() {
        Intent aosp = battery(USB, NOT_CHARGING, 0, 0);
        aosp.removeExtra("misc_event");
        aosp.removeExtra("protection");
        assertEquals(ChargingState.CHARGING, ChargingState.of(aosp));
        assertTrue(ChargingState.describe(aosp).contains("misc_event=absent"));
    }

    @Test public void aFaultIsNeverPassThrough() {
        for (int fault : new int[] {ChargingState.MISC_WATER, ChargingState.MISC_HICCUP, ChargingState.MISC_WIRELESS_FOD,
                ChargingState.MISC_TEMP_HICCUP, ChargingState.MISC_WIRELESS_OVERHEAT, ChargingState.MISC_WIRELESS_MISALIGN}) {
            assertEquals("misc 0x" + Integer.toHexString(fault), ChargingState.CHARGING,
                    ChargingState.of(battery(USB, NOT_CHARGING, 0, fault)));
        }
        assertEquals("overheated battery", ChargingState.CHARGING, ChargingState.of(battery(USB, NOT_CHARGING, 0, 0)
                .putExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_OVERHEAT)));
        assertEquals("incompatible charger", ChargingState.CHARGING, ChargingState.of(battery(USB, NOT_CHARGING, 0, 0)
                .putExtra("online", 0)));
        assertEquals("swelling protection", ChargingState.CHARGING, ChargingState.of(battery(USB, NOT_CHARGING, 0, 0)
                .putExtra("current_event", 0x10)));
    }

    @Test public void theReportLineHasTheRawValuesAndTheVerdict() {
        String line = ChargingState.describe(battery(USB, NOT_CHARGING, 1, 0x1000000));
        assertTrue(line, line.contains("protection=1") && line.contains("misc_event=0x1000000") && line.endsWith("-> protected"));
    }
}
