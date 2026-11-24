package dev.android.dynamicisland.island;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Application;
import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.source.MediaRecordingTest;
import dev.android.dynamicisland.settings.ModuleSettings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** The notification list the hook hands to the island reaches the music card's cover. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
public class MediaCoverWiringTest {
    @Test
    public void aMediaNotificationInTheListGivesThePlayerItsCover() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        var prefs = ModuleSettings.openStore(app);
        prefs.edit().clear().commit();
        ModuleSettings.configurePreview(prefs);
        var controller = ReflectionHelpers.callConstructor(IslandController.class,
                ReflectionHelpers.ClassParameter.from(android.content.Context.class, app),
                ReflectionHelpers.ClassParameter.from(ClassLoader.class, getClass().getClassLoader()));
        IslandSources sources = ReflectionHelpers.getField(controller, "sources");
        MediaController player = MediaRecordingTest.controller(app, "com.soundcloud.android", 0, "Song");
        sources.media.sync(Collections.singletonList(player));
        List<IslandItem> before = new ArrayList<>();
        sources.media.collect(before, null, new HashSet<>(), new HashSet<>());
        assertNull(before.get(0).art);

        Bitmap cover = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        cover.eraseColor(Color.RED);
        Notification n = new Notification.Builder(app, "media").setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(Icon.createWithBitmap(cover)).build();
        n.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, player.getSessionToken());
        StatusBarNotification sbn = new StatusBarNotification("com.soundcloud.android", "com.soundcloud.android", 1,
                null, 10200, 0, 0, n, android.os.Process.myUserHandle(), 0L);
        controller.onNotificationsSnapshot(Collections.singletonList(sbn));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        List<IslandItem> after = new ArrayList<>();
        sources.media.collect(after, null, new HashSet<>(), new HashSet<>());
        assertEquals(Color.RED, after.get(0).art.getPixel(2, 2));
    }
}
