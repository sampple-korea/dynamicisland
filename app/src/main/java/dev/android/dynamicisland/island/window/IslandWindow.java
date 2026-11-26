package dev.android.dynamicisland.island.window;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import dev.android.dynamicisland.island.view.IslandHost;
import dev.android.dynamicisland.island.view.IslandView;
import dev.android.dynamicisland.settings.ModuleSettings;
import dev.android.dynamicisland.xposed.XLog;
import java.lang.reflect.Field;

/**
 * The overlay window that hosts the island: a {@code TYPE_STATUS_BAR_SUB_PANEL} window inside the
 * SystemUI process containing a blur layer, the {@link IslandView} and a layer for remote views.
 *
 * <p>The window is as tall as the island currently needs and only the island's own region takes
 * touches (see {@link TouchableRegionHook}). Whole-window fade/scale is done on the surface ({@link SurfaceFader}) so
 * the exit animation does not need a re-layout. Main thread only.
 */
public final class IslandWindow {
    /** Hidden WindowManager.LayoutParams.privateFlags: show for all users | no move animation | trusted overlay. */
    private static final int PRIVATE_FLAGS = (1 << 4) | (1 << 6) | (1 << 29);
    /** Window flags: not focusable | not touch modal | layout in screen | split touch | hardware accelerated. */
    private static final int WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    /**
     * WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL (hidden): above the notification shade.
     * ADDITIONAL is below the shade: a fast fling makes the shade opaque before our time-based
     * shrink/fade finishes, abruptly covering the pill. Keep the exit visible until its own fade ends.
     * Android's window policy still places this below keyguard dialogs.
     */
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;

    final Context context;
    public final IslandView view;
    private final WindowManager windowManager;
    private final WindowManager.LayoutParams params;
    private final Root root;
    /** Holds the island's layers; turned a quarter when the island sits at a camera on the side (see {@link #setSideways}). */
    private final Turner turner;
    private final BlurLayer blur;
    private final FrameLayout remoteLayer;
    private float openDx, openDy;
    /** {@link #SIDE_NONE}, or the edge of the screen the camera is on while the island is drawn turned there. */
    private int side = SIDE_NONE;
    /** While cards are open the island at a side camera is drawn upright, so they read the right way (see below). */
    private boolean uprightWhileOpen;
    public static final int SIDE_NONE = 0;
    public static final int SIDE_LEFT = 1;
    public static final int SIDE_RIGHT = 2;
    private final Path regionPath = new Path();
    private final Region regionClip = new Region();
    private final float[] point = new float[2];
    /** Region (window coordinates) that takes touches; everything else falls through to the app below. */
    private final Region touchRegion = new Region();
    private final Region latestRegion = new Region();
    private final SurfaceFader surfaceFader = new SurfaceFader();
    private final Rect pillSamplingRegion = new Rect();
    private PillBackgroundSampler pillSampler;
    private MaterialCapture materialCapture;
    private final Rect materialRegion = new Rect();
    private boolean pillSamplingRequested;
    /** Whether the root view has been added to the WindowManager. */
    private boolean added;
    private boolean watchingOutside;
    private int requestedHeight;

    /** Callbacks for changes the window cannot handle itself. */
    public interface Listener {
        void onWindowConfigurationChanged();

        void onWindowInsetsChanged();
    }

    public IslandWindow(Context systemUiContext, IslandHost host, Listener listener) {
        context = createWindowContext(systemUiContext);
        windowManager = context.getSystemService(WindowManager.class);

        view = new IslandView(context, host);
        view.setMaterialCaptureRefresh(()->{if(materialCapture!=null)materialCapture.refresh();});
        blur = new BlurLayer(context);
        view.setBlurSink(blur);
        remoteLayer = new ActivityRemoteLayer(context,view);
        remoteLayer.setClipChildren(false);
        view.setRemoteLayer(remoteLayer);

        root = new Root(context, listener);
        root.laidOut = this::onRootLaidOut;
        root.setClipChildren(false);
        turner = new Turner(context);
        turner.setClipChildren(false);
        turner.addView(blur, matchParent());
        turner.addView(view, matchParent());
        turner.addView(remoteLayer, matchParent());
        root.addView(turner, matchParent());

        params = createLayoutParams();
        TouchableRegionHook.install(root, touchRegion);
        root.getViewTreeObserver().addOnPreDrawListener(this::onPreDraw);
        view.setPillSamplingStateChanged(this::updatePillBackgroundSampling);
    }

    // TYPE_STATUS_BAR_SUB_PANEL is a hidden framework window type used only inside SystemUI.
    @android.annotation.SuppressLint("WrongConstant")
    private static Context createWindowContext(Context systemUiContext) {
        Display display = systemUiContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        try {
            return systemUiContext.createWindowContext(display, TYPE_STATUS_BAR_SUB_PANEL, null);
        } catch (Throwable t) {
            XLog.e("createWindowContext failed, falling back to display context", t);
            return systemUiContext.createDisplayContext(display);
        }
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1, TYPE_STATUS_BAR_SUB_PANEL, WINDOW_FLAGS, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        lp.windowAnimations = 0;
        lp.setTitle("DynamicIsland");
        try {
            Field privateFlags = WindowManager.LayoutParams.class.getField("privateFlags");
            privateFlags.setInt(lp, privateFlags.getInt(lp) | PRIVATE_FLAGS);
        } catch (Throwable t) {
            XLog.e("privateFlags not set", t);
        }
        return lp;
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    public void attach() {
        if (added) {
            return;
        }
        try {
            windowManager.addView(root, params);
            added = true;
            XLog.i("island window added, height=" + params.height);
        } catch (Throwable t) {
            XLog.e("addView failed", t);
        }
    }

    public boolean isAdded() {
        return added;
    }

    void detach() {
        if (materialCapture != null) { materialCapture.destroy(); materialCapture = null; }
        if (pillSampler != null) {
            pillSampler.destroy();
            pillSampler = null;
            pillSamplingRequested = false;
        }
        if (!added) {
            return;
        }
        try {
            windowManager.removeViewImmediate(root);
        } catch (Throwable t) {
            XLog.e("removeView failed", t);
        }
        added = false;
    }

    boolean isAttached() {
        return added;
    }

    public void setShown(boolean shown) {
        int visibility = shown ? View.VISIBLE : View.GONE;
        if (root.getVisibility() != visibility) {
            root.setVisibility(visibility);
            if (!shown && pillSampler != null) {
                pillSamplingRequested = false;
                pillSampler.update(null, false);
            }
            if (!shown && materialCapture != null) materialCapture.stop();
            if (!shown) view.clearMaterialBackdrop();
        }
    }

    /** Also stop workers when the display stops producing frames before a final pre-draw can run. */
    public void stopBackdropSampling() {
        if (materialCapture != null) materialCapture.stop();
        if (pillSampler != null && pillSamplingRequested) {
            pillSamplingRequested = false;
            pillSampler.update(null, false);
        }
        view.clearMaterialBackdrop();
    }

    // ---- window geometry and touch -------------------------------------------------------------

    /** Resizes the window to the island's current extent (at least 1 px so the window stays valid). */
    public void setWindowHeight(int height) {
        if (height == requestedHeight) {
            return;
        }
        requestedHeight = height;
        applyShape();
    }

    /**
     * Draws the island turned a quarter at the camera on one side of the screen ({@link #SIDE_LEFT} or
     * {@link #SIDE_RIGHT}), the way it sits in portrait, or upright at the top again ({@link #SIDE_NONE}). The window then
     * hugs that edge: as wide as the island's height would be and as tall as the screen.
     */
    public void setSideways(int edge) {
        if (edge == side) {
            return;
        }
        side = edge;
        uprightWhileOpen = false;
        applyTurn();
    }

    public int sideways() {
        return side;
    }

    /** True while the island is drawn turned at a camera on the side (not while its cards are open upright). */
    public boolean turned() {
        return side != SIDE_NONE && !uprightWhileOpen;
    }

    /**
     * While the island at a side camera has cards open they are drawn upright, in the window across the top, so they
     * read the right way; the pill turns back to the camera once the last card is gone. No effect without a side camera.
     */
    public void setUprightWhileOpen(boolean upright) {
        if (side == SIDE_NONE || upright == uprightWhileOpen) {
            return;
        }
        uprightWhileOpen = upright;
        applyTurn();
    }

    public boolean isUprightWhileOpen() {
        return side != SIDE_NONE && uprightWhileOpen;
    }

    /**
     * The window's new shape reaches the screen a frame or two after it is asked for. Drawing the island the new way
     * inside the old window cut it (an upright card in the narrow window at the edge showed as a tall empty strip),
     * so the island turns only once the window has its new size.
     */
    private boolean turnPending;
    /** True once the island is drawn upright for open cards at a side camera (not only asked to be). */
    private boolean uprightApplied;

    private void applyTurn() {
        applyShape();
        if (!added || shapeReached()) {
            applyTurnNow();
        } else {
            turnPending = true;
        }
    }

    /** True when the window is as wide as its shape asks: the island can be drawn the new way in it. */
    private boolean shapeReached() {
        int width = root.getWidth();
        if (width <= 0) return false;
        Rect screen = windowBounds();
        int wanted = params.width == ViewGroup.LayoutParams.MATCH_PARENT ? screen.width() : params.width;
        return Math.abs(width - wanted) <= 1;
    }

    /** Called after every layout of the root: a turn waiting for the window's new shape happens now. */
    void onRootLaidOut() {
        if (turnPending && shapeReached()) {
            applyTurnNow();
        }
    }

    private void applyTurnNow() {
        turnPending = false;
        uprightApplied = isUprightWhileOpen();
        int edge = turned() ? side : SIDE_NONE;
        turner.setSide(edge);
        if (!isUprightWhileOpen()) {
            turner.setOffset(0f, 0f);
            openDx = 0f;
            openDy = 0f;
            setCardsMotion(1f, 1f, 0f, 0f);
        }
        updatePillTransform();
        // each icon and text of the pill turns back the other way, so it reads upright at the side camera (also while
        // the cards are open upright and the pills stay there)
        view.setContentTurn(side == SIDE_LEFT ? 90 : side == SIDE_RIGHT ? -90 : 0);
        blur.setSuspended(edge != SIDE_NONE);
        if (edge != SIDE_NONE) {
            // the pixels behind a turned island are not where these read them: plain pill and cards there
            stopBackdropSampling();
            view.setPillBackgroundLuma(null); // no outline decision left over from the last upright sample
        }
        invalidateTouchRegion();
    }

    /**
     * Where the upright cards of an island at a side camera are drawn: the island's own coordinates moved by
     * ({@code dx}, {@code dy}) on the window, so the cards open at that camera (see the placement). 0, 0 elsewhere.
     */
    public void setOpenOffset(float dx, float dy) {
        if (!isUprightWhileOpen()) {
            dx = 0f;
            dy = 0f;
        }
        openDx = dx;
        openDy = dy;
        if (turner.setOffset(dx, dy)) {
            invalidateTouchRegion();
        }
        updatePillTransform();
    }

    /** The pills stay where the turned island has them while its cards are open upright beside the camera. */
    private void updatePillTransform() {
        if (!isUprightWhileOpen() || !uprightApplied) {
            view.setPillTransform(null);
            return;
        }
        Rect screen = windowBounds();
        Matrix toTurned = new Matrix();
        if (side == SIDE_LEFT) {
            toTurned.setRotate(-90f);
            toTurned.postTranslate(0f, Math.min(screen.width(), screen.height()));
        } else {
            toTurned.setRotate(90f);
            toTurned.postTranslate(Math.max(screen.width(), screen.height()), 0f);
        }
        toTurned.postTranslate(-openDx, -openDy); // into the moved view's own coordinates
        view.setPillTransform(toTurned);
    }

    /**
     * The open cards only (not the pills) grow out of or shrink into a point, (px, py) in the island's own coordinates:
     * {@code shown} 0 is gone at that point, 1 is in place.
     */
    public void setCardsMotion(float shown, float minScale, float px, float py) {
        float scale = minScale + (1f - minScale) * shown;
        for (View layer : new View[] {blur, remoteLayer}) {
            layer.setPivotX(px);
            layer.setPivotY(py);
            layer.setScaleX(scale);
            layer.setScaleY(scale);
            layer.setAlpha(shown);
        }
    }

    private void applyShape() {
        int extent = Math.max(1, requestedHeight);
        if (isUprightWhileOpen()) {
            // the cards may open anywhere along the camera's edge: the window covers the screen, touches only the cards
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP | Gravity.LEFT;
        } else if (!turned()) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = extent;
            params.gravity = Gravity.TOP | Gravity.LEFT;
        } else {
            params.width = extent;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP | (side == SIDE_LEFT ? Gravity.LEFT : Gravity.RIGHT);
        }
        pushLayout();
    }

    public void setWatchOutsideTouch(boolean watch) {
        if (watchingOutside == watch) {
            return;
        }
        watchingOutside = watch;
        if (watch) {
            params.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        } else {
            params.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        }
        pushLayout();
    }

    /** Call when the island's shape changed; re-reads the touchable region on the next frame. */
    public void invalidateTouchRegion() {
        view.getTouchRegion(latestRegion);
        if (side != SIDE_NONE) toWindow(latestRegion);
        if (!latestRegion.equals(touchRegion)) {
            touchRegion.set(latestRegion);
            root.invalidate();
        }
    }

    private void pushLayout() {
        if (!added) {
            return;
        }
        try {
            windowManager.updateViewLayout(root, params);
        } catch (Throwable t) {
            XLog.e("updateViewLayout failed", t);
        }
    }

    // ---- reply bar -----------------------------------------------------------------------------

    private ReplyBar replyBar;

    /** Keep the same field, text and held card when sending failed. */
    public void replyFailed(CharSequence message) {
        if (replyBar != null) replyBar.showError(message);
    }

    /** What the reply bar reports; see {@link ReplyBar}. */
    public interface ReplyListener extends ReplyBar.Listener {
    }

    /** Shows the text field for answering below the island's cards; false when the window cannot be added. */
    public boolean showReply(CharSequence hint, CharSequence sendLabel, ReplyListener listener) {
        hideReply();
        try {
            ReplyBar bar = new ReplyBar(context, hint, sendLabel, listener);
            float density = context.getResources().getDisplayMetrics().density;
            int margin = Math.round(density * 12.0f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    windowBounds().width() - (2 * margin), ViewGroup.LayoutParams.WRAP_CONTENT, TYPE_STATUS_BAR_SUB_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            // like the input line of a chat app: at the bottom, riding on top of the keyboard where it is seen
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(density * 8.0f);
            lp.setFitInsetsTypes(WindowInsets.Type.ime() | WindowInsets.Type.navigationBars());
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
            lp.windowAnimations = 0;
            lp.setTitle("DynamicIslandReply");
            Field privateFlags = WindowManager.LayoutParams.class.getField("privateFlags");
            privateFlags.setInt(lp, privateFlags.getInt(lp) | PRIVATE_FLAGS);
            windowManager.addView(bar, lp);
            replyBar = bar;
            bar.post(bar::focusField);
            return true;
        } catch (Throwable t) {
            XLog.e("reply bar not shown", t);
            replyBar = null;
            return false;
        }
    }

    public void hideReply() {
        ReplyBar bar = replyBar;
        replyBar = null;
        if (bar != null) {
            try {
                windowManager.removeViewImmediate(bar);
            } catch (Throwable t) {
                XLog.e("reply bar not removed", t);
            }
        }
    }

    public boolean isReplying() {
        return replyBar != null;
    }

    // ---- surface fade --------------------------------------------------------------------------

    /**
     * Fades and scales the whole window surface about the pivot (px, py).
     *
     * @return false if the surface transaction is unavailable (the caller should fade another way)
     */
    /** For the diagnostics report: how much the frosted-glass backdrop filter reused its arrays. */
    public static String materialBufferStats() {
        return "backdrop filter: " + MaterialCapture.bufferStats();
    }

    public boolean setSurfaceFade(float alpha, float scale, float px, float py) {
        if (side != SIDE_NONE) {
            // the pivot comes in the island's own (upright) coordinates
            point[0] = px;
            point[1] = py;
            turner.getMatrix().mapPoints(point);
            px = point[0];
            py = point[1];
        }
        if (surfaceFader.set(alpha, scale, px, py)) {
            applySurfaceFade();
        }
        return !surfaceFader.hasFailed();
    }

    void applySurfaceFade() {
        if (added) {
            surfaceFader.apply(root);
        }
    }

    /** Re-applies the surface fade before each frame: the surface can be recreated and lose it. */
    public boolean onPreDraw() {
        if (turned()) {
            if (!surfaceFader.isNeutral()) applySurfaceFade();
            return true;
        }
        updatePillBackgroundSampling();
        try {
            boolean material = root.isHardwareAccelerated() && view.materialBackdropRegion(materialRegion);
            if (material && materialCapture == null) materialCapture = new MaterialCapture(view, view::setMaterialBackdrop);
            if (materialCapture != null) materialCapture.update(materialRegion, material, view.materialBackdropPrewarming());
        } catch (Throwable t) {
            if (materialCapture != null) materialCapture.stop();
            XLog.once("material capture placement", t);
        }
        if (!surfaceFader.isNeutral()) {
            applySurfaceFade();
        }
        return true;
    }

    private void updatePillBackgroundSampling() {
        boolean sample = ModuleSettings.pillOutlineMode() == ModuleSettings.PILL_OUTLINE_AUTO
                && view.pillBackgroundSamplingRegion(pillSamplingRegion);
        if (sample) {
            if (pillSampler == null) pillSampler = new PillBackgroundSampler(view, view::setPillBackgroundLuma);
            pillSamplingRequested = true;
            pillSampler.update(pillSamplingRegion, true);
        } else if (pillSampler != null && pillSamplingRequested) {
            pillSamplingRequested = false;
            pillSampler.update(null, false);
        }
    }

    // ---- display info --------------------------------------------------------------------------

    public Rect windowBounds() {
        return windowManager.getCurrentWindowMetrics().getBounds();
    }

    public DisplayCutout cutout() {
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null && insets.getDisplayCutout() != null) {
            return insets.getDisplayCutout();
        }
        try {
            Display display = context.getDisplay();
            return display != null ? display.getCutout() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The cutout in screen coordinates for the current rotation: the window's own insets are relative to where it is. */
    public DisplayCutout displayCutout() {
        try {
            Display display = context.getDisplay();
            DisplayCutout cutout = display != null ? display.getCutout() : null;
            if (cutout != null) return cutout;
        } catch (Throwable ignored) {
            // fall back to the window insets below
        }
        return cutout();
    }

    int rotation() {
        try {
            Display display = context.getDisplay();
            return display != null ? display.getRotation() : Surface.ROTATION_0;
        } catch (Throwable ignored) {
            return Surface.ROTATION_0;
        }
    }

    /** Maps a region from the island's own coordinates to the window's while it is drawn turned. */
    private void toWindow(Region region) {
        if (region.isEmpty()) return;
        regionPath.rewind();
        region.getBoundaryPath(regionPath);
        regionPath.transform(turner.getMatrix());
        regionClip.set(-100_000, -100_000, 100_000, 100_000);
        region.setPath(regionPath, regionClip);
    }

    /**
     * Lays its children out upright and, for a camera on a side, turns them a quarter about its top-left corner and
     * moves them back onto the window: measured with the window's width and height swapped, the island inside still
     * sees a portrait status bar. Touches follow the turn on their own (the parent maps them through this matrix).
     */
    static final class Turner extends FrameLayout {
        private int side = SIDE_NONE;
        /** Extra shift of the upright island (cards opened at a side camera). */
        private float offsetX;
        private float offsetY;

        Turner(Context context) {
            super(context);
            setPivotX(0f);
            setPivotY(0f);
        }

        void setSide(int edge) {
            side = edge;
            setRotation(edge == SIDE_LEFT ? -90f : edge == SIDE_RIGHT ? 90f : 0f);
            place();
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (side == SIDE_NONE) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            } else {
                super.onMeasure(heightMeasureSpec, widthMeasureSpec);
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            place();
        }

        boolean setOffset(float dx, float dy) {
            if (dx == offsetX && dy == offsetY) return false;
            offsetX = dx;
            offsetY = dy;
            place();
            return true;
        }

        /** After the turn the upright box lies above (left side) or left of (right side) the window: move it back. */
        private void place() {
            setTranslationX(side == SIDE_RIGHT ? getHeight() : side == SIDE_NONE ? offsetX : 0f);
            setTranslationY(side == SIDE_LEFT ? getWidth() : side == SIDE_NONE ? offsetY : 0f);
        }
    }

    /** Root view of the window; forwards configuration and inset changes to the {@link Listener}. */
    private static final class Root extends FrameLayout {
        private final Listener listener;

        Root(Context context, Listener listener) {
            super(context);
            this.listener = listener;
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (laidOut != null) laidOut.run();
        }

        Runnable laidOut;

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            listener.onWindowConfigurationChanged();
        }

        @Override
        public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            WindowInsets result = super.onApplyWindowInsets(insets);
            post(listener::onWindowInsetsChanged);
            return result;
        }
    }
}
