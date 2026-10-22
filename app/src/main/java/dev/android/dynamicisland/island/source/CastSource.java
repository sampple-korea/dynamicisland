package dev.android.dynamicisland.island.source;

import android.content.Context;
import android.os.SystemClock;
import android.view.View;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.xposed.Refl;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Screen recording, screen sharing and casting as island items, read from the chip model of System UI's
 * screen-record / cast status bar controller ({@code screenRecord}, {@code shareToApp},
 * {@code castToOtherDevice}). Only the {@code Active} states of those models are shown.
 */
public final class CastSource {
    private static final int BLUE = 0xFF0A84FF;
    private static final int RED = -50384;

    /** System UI's cast controller; its {@code chipView} is clicked to stop. Set by the hooks. */
    public Object castController;
    private Object bundle;

    /** {@code ctx} is accepted for symmetry with the other sources; the chip model needs no context. */
    public CastSource(Context ctx) {
    }

    /** Latest chip models of the status bar (a bundle of the three chip states). */
    public void onBundle(Object b) {
        bundle = b;
    }

    public void collect(List<IslandItem> out) {
        if (bundle == null) {
            return;
        }
        Object record = Refl.get(bundle, "screenRecord");
        Object share = Refl.get(bundle, "shareToApp");
        Object cast = Refl.get(bundle, "castToOtherDevice");
        if (isActive(record)) {
            out.add(item("cast:record", record, IslandItem.Kind.RECORDING, RED, tr("Screen recording", "Запись экрана")));
        } else if (isActive(share)) {
            String manager = (String) Refl.get(share, "managingPackageName");
            // Samsung's own recorder reports itself as a share
            boolean ownRecorder = manager != null && manager.contains("com.samsung.android.app.smartcapture");
            String title = ownRecorder ? tr("Screen recording", "Запись экрана") : tr("Sharing screen", "Демонстрация экрана");
            out.add(item("cast:share", share, IslandItem.Kind.RECORDING, RED, title));
        }
        if (isActive(cast)) {
            out.add(item("cast:device", cast, IslandItem.Kind.LIVE, BLUE, tr("Casting screen", "Трансляция экрана")));
        }
    }

    private IslandItem item(String key, Object model, IslandItem.Kind kind, int accent, String title) {
        IslandItem it = new IslandItem(key);
        it.kind = kind;
        it.accent = accent;
        it.pulsingDot = kind == IslandItem.Kind.RECORDING;
        it.title = title;
        it.appName = title;
        it.packageName = (String) Refl.get(model, "managingPackageName");
        Clock clock = readClock(Refl.get(model, "content"));
        if (clock != null) {
            it.trailing = IslandItem.Trailing.CHRONOMETER;
            it.chronoBase = clock.base;
            it.chronoCountDown = clock.countDown;
        }
        Object chip = castController == null ? null : Refl.get(castController, "chipView");
        if (chip instanceof View) {
            it.actions.add(new IslandItem.Action(tr("Stop", "Остановить"), null, null, () -> clickStopChip((View) chip), true));
        }
        it.postTime = Long.MAX_VALUE - 1; // above everything else of its kind
        return it;
    }

    /** Taps the chip the way the status bar does: through its custom listener when it has one. */
    static void clickStopChip(View chip) {
        Object listener = Refl.get(chip, "customClickListener");
        if (listener instanceof View.OnClickListener) {
            ((View.OnClickListener) listener).onClick(chip);
        } else {
            chip.performClick();
        }
    }

    private static String tr(String english, String russian) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    private static boolean isActive(Object model) {
        return model != null && model.getClass().getName().endsWith("$Active");
    }

    /** Wall-clock base of a chronometer and its direction. */
    private static final class Clock {
        final long base;
        final boolean countDown;

        Clock(long base, boolean countDown) {
            this.base = base;
            this.countDown = countDown;
        }
    }

    /** The timer in a chip's content: running (up or down) or paused at a duration; null for anything else. */
    private static Clock readClock(Object content) {
        if (content == null || !content.getClass().getName().endsWith("$Timer")) {
            return null;
        }
        Object state = Refl.get(content, "value");
        if (state == null) {
            return null;
        }
        String name = state.getClass().getName();
        if (name.endsWith("$Running")) {
            return runningClock(state);
        }
        if (name.endsWith("$Paused") && Refl.get(state, "atDuration") instanceof Duration) {
            Duration elapsed = (Duration) Refl.get(state, "atDuration");
            return new Clock(System.currentTimeMillis() - elapsed.toMillis(), false);
        }
        return null;
    }

    private static Clock runningClock(Object running) {
        boolean countDown = Refl.getBool(running, "isCountdown", false);
        Object event = Refl.get(running, "eventTime");
        if (event == null) {
            return null;
        }
        Object elapsedRealtime = Refl.get(event, "elapsedRealtime");
        if (elapsedRealtime instanceof Long) {
            long age = SystemClock.elapsedRealtime() - (Long) elapsedRealtime;
            return new Clock(System.currentTimeMillis() - age, countDown);
        }
        Object instant = Refl.get(event, "instant");
        return instant instanceof Instant ? new Clock(((Instant) instant).toEpochMilli(), countDown) : null;
    }
}
