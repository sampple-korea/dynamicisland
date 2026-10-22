package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HideLiveSummaryTest {
    private static Notification notification(boolean summary, boolean live) {
        Notification n = new Notification();
        if (summary) {
            n.flags |= Notification.FLAG_GROUP_SUMMARY;
        }
        if (live) {
            n.extras.putInt("android.ongoingActivityNoti.style", 1);
        }
        return n;
    }

    @Test
    public void aLiveGroupSummaryIsHidden() {
        assertTrue(HideDuplicated.isLiveSummary(notification(true, true)));
    }

    @Test
    public void aLiveCardOfTheGroupStaysVisible() {
        assertFalse(HideDuplicated.isLiveSummary(notification(false, true)));
    }

    @Test
    public void anOrdinarySummaryStaysVisible() {
        assertFalse(HideDuplicated.isLiveSummary(notification(true, false)));
    }
}
