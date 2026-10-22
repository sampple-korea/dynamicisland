package dev.android.dynamicisland.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import dev.android.dynamicisland.island.feedback.AudioLevels;
import dev.android.dynamicisland.island.feedback.Haptics;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.island.window.IslandWindow;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.Diagnostics;
import dev.android.dynamicisland.xposed.XLog;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade of the island: the single per-process entry point for the Xposed hooks and the callback
 * target of the {@link IslandView} and its window. The work lives in focused collaborators:
 * {@link IslandSources} (gathering), {@link ItemLedger} (ordering, deferral, minimizing, cycling),
 * {@link IslandPlacement} (window, geometry, suppression), {@link HeadsUpTakeover} (banner and
 * verification-code cards), {@link ItemLauncher} and {@link ShadeDragRelay}.
 *
 * <p>There is one instance per System UI process ({@link #init}, {@link #get}). Every state change
 * ends in {@link #requestRefresh()}, which coalesces requests and rebuilds the list on the main
 * thread. Unless noted, everything here runs on the main thread; the {@code set*} / {@code on*}
 * entry points that hooks may call from other threads either post to the main thread or only touch
 * main-thread-safe state.
 */
public final class IslandController implements IslandHost, IslandWindow.Listener {
    /** Never re-run a deferred refresh sooner than one frame. */
    private static final long MIN_DEFERRED_REFRESH_MS = 16L;

    private static IslandController instance;

    private final Context ctx;
    /** All callbacks and UI work run here. */
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioLevels audio = new AudioLevels();
    private final Haptics haptics;

    private final IslandSources sources;
    private final ItemLedger ledger = new ItemLedger();
    private final IslandPlacement placement;
    private final HeadsUpTakeover headsUp;
    private final ItemLauncher launcher;
    private final ShadeDragRelay shadeDrag;

    private boolean refreshPosted;
    private final Runnable refreshRunnable = this::refreshNow;

    private IslandController(Context ctx, ClassLoader classLoader) {
        this.ctx = ctx;
        sources = new IslandSources(ctx, classLoader, main, this::requestRefresh);
        haptics = new Haptics(ctx);
        placement = new IslandPlacement(ctx, main, classLoader, this, this, audio);
        headsUp = new HeadsUpTakeover(sources, placement, this::refreshNow);
        launcher = new ItemLauncher(ctx, haptics, placement::view);
        shadeDrag = new ShadeDragRelay(ctx, placement::statusBar);
    }

    /** Creates the controller on first call (from the System UI hook) and returns it. */
    public static synchronized IslandController init(Context ctx, ClassLoader classLoader) {
        if (instance == null) {
            ModuleSettings.ensureStarted(); // start reading the settings now, not on the first getter call
            instance = new IslandController(ctx, classLoader);
            instance.main.post(instance::start);
            XLog.i("controller created");
        }
        return instance;
    }

    /** The controller, or null before {@link #init}. */
    public static IslandController get() {
        return instance;
    }

    // ---- public API used by the hooks ----------------------------------------------------------

    public IslandItem itemForKey(String notificationKey) {
        return sources.oa.itemForKey(notificationKey);
    }

    public IslandItem mediaItemForSession(MediaSession.Token token) {
        return sources.media.itemForSession(token);
    }

    public void start() {
        sources.start(this::requestRefresh);
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    requestRefresh();
                }
            }, new IntentFilter("android.media.action.MICROPHONE_MUTE_CHANGED"), Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            XLog.e("mute receiver failed", t);
        }
        try {
            ctx.getSystemService(AudioManager.class)
                    .addOnCommunicationDeviceChangedListener(ctx.getMainExecutor(), device -> requestRefresh());
        } catch (Throwable t) {
            XLog.e("speaker listener failed", t);
        }
        requestRefresh();
        placement.scheduleFallbackWindow();
    }

    /** Schedules one list rebuild on the main thread; many requests in a row cost a single refresh. Any thread. */
    public void requestRefresh() {
        if (Looper.myLooper() != main.getLooper()) {
            main.post(this::requestRefresh);
        } else if (!refreshPosted) {
            refreshPosted = true;
            main.post(refreshRunnable);
        }
    }

    public void onStatusBarViewAttached(View statusBar) {
        if (placement.statusBar() == statusBar) {
            return;
        }
        placement.statusBarAttached(statusBar);
        requestRefresh();
    }

    public void onStatusBarViewDetached(View statusBar) {
        placement.statusBarDetached(statusBar);
    }

    /**
     * A heads-up banner is about to be shown: take it over as an island card when the island can show
     * it right now.
     *
     * @return true when the island took it (the stock banner must stay away)
     */
    public boolean peekHeadsUp(StatusBarNotification sbn) {
        return headsUp.take(sbn);
    }

    /** True when the island shows a card for this notification and the setting asks to drop the shade row. */
    public boolean hidesOriginal(String notificationKey) {
        return ModuleSettings.hideProgressOriginals() && sources.progress.isShownOnIsland(notificationKey);
    }

    /** Fed by the notification list hook: progress notifications become cards. */
    public void onNotificationsSnapshot(List<StatusBarNotification> all) {
        List<StatusBarNotification> allowed = new ArrayList<>(all.size());
        for (StatusBarNotification sbn : all) {
            if (sbn == null || ModuleSettings.appAllowed(sbn.getPackageName())) {
                allowed.add(sbn);
            }
        }
        sources.progress.update(allowed);
        sources.rules.update(allowed);
        List<StatusBarNotification> forCodes = new ArrayList<>(allowed);
        Runnable scan = () -> {
            try {
                headsUp.scanForCodes(forCodes);
            } catch (Throwable t) {
                XLog.once("IslandController.showNewCodes", t);
            }
        };
        // the card touches views: only ever from the main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            scan.run();
        } else {
            main.post(scan);
        }
        sources.calls.update(all);
    }

    public void setOngoingActivityController(Object controller) {
        placement.setOngoingActivityController(controller);
        requestRefresh();
    }

    public void setCallController(Object controller) {
        sources.oa.callController = controller;
    }

    public void setCastBundle(Object bundle) {
        sources.cast.onBundle(bundle);
        requestRefresh();
    }

    public void setCastController(Object controller) {
        sources.cast.castController = controller;
    }

    /** @param state SystemUI StatusBarState: 0 shade, 1 keyguard, 2 locked shade */
    public void setBarState(int state) {
        placement.setBarState(state);
    }

    public void setDozing(boolean value) {
        placement.setDozing(value);
    }

    /** @param state non-zero while the status bar is hidden (full-screen app) */
    public void setStatusBarWindowState(int state) {
        placement.setStatusBarWindowState(state);
    }

    public void setShadeExpansion(float fraction) {
        IslandView view = placement.view();
        if (view != null) {
            view.setShadeFraction(fraction);
        }
    }

    /** The launcher asked for the island position (it just started an app-close animation). */
    public void onExitTargetsRequested() {
        placement.pushExitTargets(true);
    }

    // ---- IslandHost ----------------------------------------------------------------------------

    @Override
    public boolean onShadeFade(float alpha, float scale, float px, float py) {
        IslandWindow window = placement.window();
        return window != null && window.setSurfaceFade(alpha, scale, px, py);
    }

    @Override
    public void onIslandFrame() {
        placement.onFrame();
    }

    @Override
    public void onOpenItem(IslandItem item, RectF from) {
        launcher.open(item, from);
    }

    @Override
    public void onItemAction(IslandItem item, IslandItem.Action action) {
        launcher.runAction(action);
    }

    @Override
    public void onExpandedChanged(boolean expanded) {
        haptics.tick();
        IslandWindow window = placement.window();
        if (window != null) {
            window.setWatchOutsideTouch(expanded);
        }
        placement.pushExitTargets(false);
    }

    @Override
    public void onCycle(int direction) {
        if (ledger.cycle(direction)) {
            requestRefresh();
        }
    }

    @Override
    public void onShadeDragStart(float downX, float downY, long downTime) {
        shadeDrag.start(downX, downY, downTime);
    }

    @Override
    public void onShadeDragEvent(MotionEvent event) {
        shadeDrag.move(event);
    }

    @Override
    public void onMinimize(IslandItem item) {
        if (ledger.minimize(item)) {
            requestRefresh();
        }
    }

    @Override
    public void onRestore() {
        if (ledger.restore()) {
            requestRefresh();
        }
    }

    @Override
    public boolean hasMinimized() {
        return ledger.hasMinimized();
    }

    @Override
    public void onWindowHeightNeeded(int px) {
        placement.setNeededHeight(px);
    }

    @Override
    public AudioLevels audio() {
        return audio;
    }

    // ---- IslandWindow.Listener -----------------------------------------------------------------

    @Override
    public void onWindowConfigurationChanged() {
        main.post(placement::updateGeometry);
    }

    @Override
    public void onWindowInsetsChanged() {
        placement.updateGeometry();
    }

    // ---- refresh -------------------------------------------------------------------------------

    /** Rebuilds the item list from all sources and hands it to the view. A failure only skips this refresh. */
    private void refreshNow() {
        try {
            refreshPosted = false;
            placement.ensureWindow(false);
            IslandView view = placement.view();
            if (view == null) {
                return;
            }
            IslandSources.Gathered gathered = sources.gather();
            long now = SystemClock.uptimeMillis();
            ItemLedger.Arrangement shape = ledger.arrange(gathered.items, gathered.hiddenByForeground, now);
            view.setItems(shape.shown, shape.hint, shape.bubbled);
            Diagnostics.fact("islands", shape.shown.size() + " shown, " + shape.bubbled.size() + " minimized");
            Diagnostics.publish(ctx);
            if (shape.flyIn) {
                view.absorb();
            }
            if (shape.nextDeferredAt != Long.MAX_VALUE) {
                main.postDelayed(this::requestRefresh, Math.max(MIN_DEFERRED_REFRESH_MS, shape.nextDeferredAt - now));
            }
            placement.updateExitPackages(gathered.packages);
            onIslandFrame();
        } catch (Throwable t) {
            refreshPosted = false;
            XLog.once("IslandController.refreshNow", t);
        }
    }
}
