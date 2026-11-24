package dev.android.dynamicisland.island.source;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import dev.android.dynamicisland.island.model.IslandItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A player that keeps its cover only in the large icon of its media notification still gets a cover and palette. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MediaNotificationArtTest {
    private static final String PKG = "com.soundcloud.android";
    private final Application app = RuntimeEnvironment.getApplication();
    private final AtomicInteger changes = new AtomicInteger();

    private MediaSource source() {
        return new MediaSource(app, new Handler(Looper.getMainLooper()), changes::incrementAndGet);
    }

    private static Bitmap cover(int colour) {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(colour);
        return bitmap;
    }

    private StatusBarNotification mediaNotification(MediaController controller, Icon largeIcon) {
        Notification n = new Notification.Builder(app, "media").setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(largeIcon).build();
        n.extras.putParcelable(Notification.EXTRA_MEDIA_SESSION, controller.getSessionToken());
        return new StatusBarNotification(PKG, PKG, 1, null, 10200, 0, 0, n, android.os.Process.myUserHandle(), 0L);
    }

    private IslandItem card(MediaSource source) {
        List<IslandItem> out = new ArrayList<>();
        source.collect(out, null, new HashSet<>(), new HashSet<>());
        return out.isEmpty() ? null : out.get(0);
    }

    @Test
    public void largeIconOfTheMediaNotificationIsTheCoverWhenTheMetadataHasNone() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        assertNull("without the notification there is no cover", card(source).art);

        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithBitmap(cover(Color.RED)))));
        IslandItem item = card(source);
        assertNotNull(item.art);
        assertEquals(Color.RED, item.art.getPixel(3, 3));
        assertNotNull("the palette comes from the cover", item.mediaPalette);
    }

    @Test
    public void metadataBitmapStillWinsOverTheNotificationIcon() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        android.media.MediaMetadata withArt = new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Song")
                .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, cover(Color.BLUE)).build();
        org.robolectric.Shadows.shadowOf(player).setMetadata(withArt);
        source.sync(Collections.singletonList(player));
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithBitmap(cover(Color.RED)))));
        assertEquals(Color.BLUE, card(source).art.getPixel(3, 3));
    }

    @Test
    public void theSamePictureAgainDoesNotRefreshTheCardOrReplaceTheCover() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithBitmap(cover(Color.GREEN)))));
        Bitmap first = card(source).art;
        int before = changes.get();
        // a new notification object with an equal picture, as every list rebuild may give
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithBitmap(cover(Color.GREEN)))));
        assertEquals(before, changes.get());
        assertSame(first, card(source).art);
    }

    @Test
    public void aNewPictureReplacesTheCover() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithBitmap(cover(Color.RED)))));
        assertEquals(Color.RED, card(source).art.getPixel(1, 1));
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithBitmap(cover(Color.YELLOW)))));
        assertEquals(Color.YELLOW, card(source).art.getPixel(1, 1));
    }

    @Test
    public void aGoneNotificationNoLongerGivesACoverToAnotherTrack() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithBitmap(cover(Color.RED)))));
        assertNotNull(card(source).art);
        source.updateNotificationArt(Collections.emptyList());
        // the next track has no bitmap and no notification any more: after the grace period nothing is left
        org.robolectric.Shadows.shadowOf(player).executeOnMetadataChanged(
                new android.media.MediaMetadata.Builder()
                        .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Other song").build());
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); // the callback arrives through the handler
        assertNotNull("the old cover is kept for a moment, as before", card(source).art);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
        assertNull(card(source).art);
    }

    @Test
    public void aLargeCoverIsScaledDown() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        Bitmap big = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888);
        big.eraseColor(Color.CYAN);
        source.updateNotificationArt(Collections.singletonList(mediaNotification(player, Icon.createWithBitmap(big))));
        Bitmap art = card(source).art;
        assertTrue(Math.max(art.getWidth(), art.getHeight()) <= 512);
        assertEquals(Color.CYAN, art.getPixel(2, 2));
    }

    private MediaController playerWithArtUri(String uri) throws Exception {
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        org.robolectric.Shadows.shadowOf(player).setMetadata(new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "Song")
                .putString(android.media.MediaMetadata.METADATA_KEY_ART_URI, uri).build());
        return player;
    }

    @Test
    public void aMetadataArtUriIsLoadedInTheBackgroundAndBecomesTheCover() throws Exception {
        MediaSource source = source();
        List<String> asked = new ArrayList<>();
        List<Runnable> queued = new ArrayList<>();
        source.setUriLoader(queued::add, uri -> {
            asked.add(uri);
            return cover(Color.MAGENTA);
        });
        MediaController player = playerWithArtUri("content://com.soundcloud.android.artwork/1");
        source.sync(Collections.singletonList(player));
        assertNull("the card does not wait for the picture", card(source).art);
        assertEquals("nothing is read on the main thread", 0, asked.size());
        assertEquals(1, queued.size());
        card(source); // rebuilds while the load is under way do not start another one
        assertEquals(1, queued.size());

        queued.get(0).run();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Collections.singletonList("content://com.soundcloud.android.artwork/1"), asked);
        assertEquals(Color.MAGENTA, card(source).art.getPixel(2, 2));
        assertEquals("loaded once", 1, asked.size());
    }

    @Test
    public void onlyContentFileAndResourceUrisAreLoaded() throws Exception {
        MediaSource source = source();
        List<Runnable> queued = new ArrayList<>();
        source.setUriLoader(queued::add, uri -> cover(Color.MAGENTA));
        source.sync(Collections.singletonList(playerWithArtUri("https://i1.sndcdn.com/artworks-1.jpg")));
        card(source);
        assertTrue("a web address is not read", queued.isEmpty());
        assertTrue(MediaSource.loadableUri("content://a/b"));
        assertTrue(MediaSource.loadableUri("file:///sdcard/a.png"));
        assertTrue(MediaSource.loadableUri("android.resource://pkg/1"));
        assertTrue(!MediaSource.loadableUri("http://a/b") && !MediaSource.loadableUri("") && !MediaSource.loadableUri(null));
    }

    @Test
    public void aFailedUriIsNotTriedAgainOnEveryRebuild() throws Exception {
        MediaSource source = source();
        List<Runnable> queued = new ArrayList<>();
        source.setUriLoader(queued::add, uri -> null);
        source.sync(Collections.singletonList(playerWithArtUri("content://x/broken")));
        card(source);
        queued.get(0).run();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        card(source);
        card(source);
        assertEquals(1, queued.size());
        assertNull(card(source).art);
    }

    @Test
    public void aLargeIconThatIsAUriIsLoadedInTheBackground() throws Exception {
        MediaSource source = source();
        List<Runnable> queued = new ArrayList<>();
        source.setUriLoader(queued::add, uri -> cover(Color.RED));
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithContentUri("content://com.soundcloud.android.artwork/2"))));
        assertNull(card(source).art);
        assertEquals(1, queued.size());
        queued.get(0).run();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Color.RED, card(source).art.getPixel(1, 1));
    }

    @Test
    public void aResourceIconIsTheAppsOwnPictureNotACover() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        source.updateNotificationArt(Collections.singletonList(
                mediaNotification(player, Icon.createWithResource(app, android.R.drawable.ic_media_play))));
        assertNull(card(source).art);
    }

    @Test
    public void notificationsThatAreNotMediaAreIgnored() throws Exception {
        MediaSource source = source();
        MediaController player = MediaRecordingTest.controller(app, PKG, 0, "Song");
        source.sync(Collections.singletonList(player));
        Notification plain = new Notification.Builder(app, "x").setSmallIcon(android.R.drawable.ic_media_play)
                .setLargeIcon(Icon.createWithBitmap(cover(Color.RED))).build();
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, 2, null, 10200, 0, 0, plain,
                android.os.Process.myUserHandle(), 0L);
        int before = changes.get();
        source.updateNotificationArt(Collections.singletonList(sbn));
        source.updateNotificationArt(null);
        assertEquals(before, changes.get());
        assertNull(card(source).art);
    }
}
