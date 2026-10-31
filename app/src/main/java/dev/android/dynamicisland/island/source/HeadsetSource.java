package dev.android.dynamicisland.island.source;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.SystemClock;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import java.util.List;

/**
 * Wired headphones.
 *
 * <p>Plugging them in shows a card that stays for as long as they are connected (like the charging
 * card), because the connection is a state you may want to see at a glance. Unplugging shows a short
 * popup that disappears on its own, like a notification.
 *
 * <p>{@code AudioManager.ACTION_HEADSET_PLUG} is sticky, so registering reports the state the phone is
 * already in; that first report only seeds the state and never pops a card, otherwise opening the module
 * would announce headphones that were plugged in hours ago.
 */
public final class HeadsetSource {

    /** How long the "disconnected" popup stays on screen. */
    private static final long POPUP_MS = 2500L;
    /**
     * How long the "connected" card is offered. The island opens it expanded (about 4 s), collapses it to
     * the pill, and the card then goes away by itself - the connection is announced, not kept on screen.
     */
    private static final long CONNECT_MS = 6000L;
    /** How long the island stays open when the card arrives. */
    private static final long PEEK_MS = 4000L;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;

    /** 1 = headphones connected, 0 = not, -1 = not known yet. */
    private int state = -1;
    private String name;
    /** When the disconnect popup stops being offered; 0 = no popup. */
    private long popupUntil;
    /** When the connected card stops being offered; 0 = not announced. */
    private long connectedUntil;
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i == null) {
                return;
            }
            int s = i.getIntExtra("state", 0);
            String deviceName = i.getStringExtra("name");
            boolean first = state < 0;
            state = s;
            name = deviceName;
            if (first) {
                // the sticky broadcast on registration: only learn the state, do not announce it
                onChange.run();
                return;
            }
            if (s == 0) {
                connectedUntil = 0;
                popupUntil = SystemClock.uptimeMillis() + POPUP_MS;
                main.removeCallbacks(expire);
                main.postDelayed(expire, POPUP_MS + 50);
            } else {
                popupUntil = 0;
                connectedUntil = SystemClock.uptimeMillis() + CONNECT_MS;
                main.removeCallbacks(expire);
                main.postDelayed(expire, CONNECT_MS + 50);
            }
            onChange.run();
        }
    };

    private final Runnable expire = new Runnable() {
        @Override
        public void run() {
            popupUntil = 0;
            connectedUntil = 0;
            onChange.run();
        }
    };

    public HeadsetSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        try {
            ctx.registerReceiver(receiver, new IntentFilter(AudioManager.ACTION_HEADSET_PLUG));
        } catch (Throwable t) {
            XLog.e("headset receiver not registered", t);
        }
    }

    /** Adds the connected card while it is being announced, or the short popup after unplugging. */
    public void collect(List<IslandItem> out) {
        if (!ModuleSettings.eventHeadset()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (state == 1 && connectedUntil > now) {
            out.add(connectedCard(connectedUntil));
        } else if (state == 0 && popupUntil > now) {
            out.add(disconnectedPopup());
        }
    }

    /** Announced once when the headphones are plugged in, then it goes away like a notification. */
    private IslandItem connectedCard(long until) {
        IslandItem it = base("headset:connected");
        it.title = t("이어폰 연결됨", "Headphones connected");
        it.subtitle = name == null || name.isEmpty() ? t("유선 이어폰", "Wired headphones") : name;
        it.icon = GlyphIcon.of(GlyphIcon.Kind.HEADPHONES);
        it.accent = -1;
        it.postTime = until;            // stable for this announcement, so the island opens it once
        it.expiresAtUptime = until;
        it.peekMillis = PEEK_MS;        // open expanded when it arrives
        return it;
    }

    /** Goes away by itself, the way a notification does. */
    private IslandItem disconnectedPopup() {
        IslandItem it = base("headset:disconnected");
        it.title = t("이어폰 분리됨", "Headphones disconnected");
        it.icon = GlyphIcon.of(GlyphIcon.Kind.HEADPHONES);
        it.accent = -1;
        it.postTime = popupUntil;
        it.expiresAtUptime = popupUntil;
        it.peekMillis = PEEK_MS;
        return it;
    }

    private IslandItem base(String key) {
        IslandItem it = new IslandItem(key);
        it.kind = IslandItem.Kind.EVENT;
        it.iconIsImage = false;
        it.pillColor = 0;
        return it;
    }

    private String t(String ko, String en) {
        java.util.Locale locale = ctx.getResources().getConfiguration().getLocales().get(0);
        return "ko".equals(locale.getLanguage()) ? ko : en;
    }
}
