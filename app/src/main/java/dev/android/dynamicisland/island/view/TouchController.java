package dev.android.dynamicisland.island.view;

import static dev.android.dynamicisland.island.graphics.Easing.rubber;

import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import dev.android.dynamicisland.island.graphics.Motion;
import dev.android.dynamicisland.island.view.card.Btn;
import dev.android.dynamicisland.island.view.card.MediaBody;

/**
 * Turns touches on the island into actions: pressing and tapping the pill, swiping it away or back, handing a
 * downward drag over to the notification shade, dragging the open card stack, pressing card buttons and
 * scrubbing the music progress bar.
 */
final class TouchController {
    private static final float TOUCH_MARGIN_DP = 6.0f;
    /** Below this overall alpha the island ignores touches. */
    static final float MIN_TOUCHABLE_ALPHA = 0.4f;
    private static final float SWIPE_MIN_DP = 26.0f;
    private static final float SWIPE_DOMINANCE = 1.2f;
    private static final float SWIPE_UP_DISMISS_DP = 48.0f;
    private static final int HAPTIC_SEEK_DONE = HapticFeedbackConstants.CLOCK_TICK;
    private static final int HAPTIC_CONFIRM = HapticFeedbackConstants.CONTEXT_CLICK;

    private enum Axis { UNDECIDED, HORIZONTAL, VERTICAL }

    private final IslandView view;
    private final IslandHost host;
    private final Metrics m;
    private final Scene scene;
    private final Springs sp;
    private final Shapes shapes;
    private final Director director;
    private final ButtonPress buttons;
    private final float touchSlop;
    private final RectF slot = new RectF();

    private float downX;
    private float downY;
    private boolean dragging;
    private boolean downOnPill;
    private boolean downOnBubble;
    /** Touch started on the empty hole while an item is minimized there. */
    private boolean downOnHole;
    private Card downCard;
    /** The gesture already did its job (e.g. collapsed the stack): swallow the rest of it. */
    private boolean ignoreGesture;
    /** A downward drag on the pill is being forwarded to the notification shade. */
    private boolean forwardingShade;
    private Axis axis = Axis.UNDECIDED;
    private boolean stackDragging;

    TouchController(IslandView view, IslandHost host, Metrics m, Scene scene, Shapes shapes, Director director, ButtonPress buttons) {
        this.view = view;
        this.host = host;
        this.m = m;
        this.scene = scene;
        this.sp = scene.springs;
        this.shapes = shapes;
        this.director = director;
        this.buttons = buttons;
        this.touchSlop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
    }

    boolean handle(MotionEvent e) {
        int action = e.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && ignoreGesture) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                ignoreGesture = false;
            }
            return true;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                down(e);
                break;
            case MotionEvent.ACTION_UP:
                up(e);
                break;
            case MotionEvent.ACTION_MOVE:
                move(e);
                break;
            case MotionEvent.ACTION_CANCEL:
                cancel(e);
                break;
            case MotionEvent.ACTION_OUTSIDE:
                if (scene.mode == Mode.EXPANDED) {
                    director.collapse();
                }
                break;
            default:
                break;
        }
        return true;
    }

    // ---- down -------------------------------------------------------------------------------------------------------

    private void down(MotionEvent e) {
        ignoreGesture = false;
        axis = Axis.UNDECIDED;
        downX = e.getX();
        downY = e.getY();
        dragging = false;
        downOnPill = false;
        downOnBubble = false;
        downCard = null;
        downOnHole = false;
        if (scene.mode == Mode.HIDDEN) {
            downOnHole = host.hasMinimized();
            return;
        }
        if (scene.visibleAlpha() < MIN_TOUCHABLE_ALPHA) {
            return;
        }
        float margin = m.dp(TOUCH_MARGIN_DP);
        if (scene.mode == Mode.EXPANDED) {
            downOnCards(margin);
        } else {
            downOnIsland(margin);
        }
    }

    private void downOnCards(float margin) {
        for (Card c : scene.cards) {
            if (!c.leaving) {
                shapes.card(c, slot);
                slot.inset(-margin, -margin);
                if (slot.contains(downX, downY)) {
                    downCard = c;
                }
            }
        }
        if (downCard == null) {
            // touch outside every card closes the stack
            director.collapse();
            ignoreGesture = true;
            return;
        }
        director.cancelAutoCollapse();
        shapes.card(downCard, slot);
        float left = shapes.contentLeft(downCard);
        if (downCard.body instanceof MediaBody) {
            MediaBody media = (MediaBody) downCard.body;
            if (media.onSeekBar(downX - left, downY - slot.top)) {
                view.seekBody = media;
                view.seekFraction = media.fractionAt(downX - left);
                view.getParent().requestDisallowInterceptTouchEvent(true);
                view.invalidate();
            }
        }
        if (view.seekBody == null) {
            Btn pressed = downCard.body.hit(downX - left, downY - slot.top);
            if (pressed != null) {
                buttons.press(pressed, downCard.item);
                view.scheduleFrame();
                view.invalidate();
            }
        }
    }

    private void downOnIsland(float margin) {
        shapes.main(slot);
        downOnPill = downX >= slot.left - margin && downX <= slot.right + margin
                && downY >= slot.top - margin && downY <= slot.bottom + margin;
        if (!downOnPill && sp.split.value() > 0.6f) {
            RectF bubble = new RectF();
            shapes.bubble(slot, bubble);
            bubble.inset(-margin, -margin);
            downOnBubble = bubble.contains(downX, downY);
        }
        if (downOnPill || downOnBubble) {
            sp.press.animateTo(0.94f, Motion.PRESS_DOWN);
            view.scheduleFrame();
        }
    }

    // ---- move -------------------------------------------------------------------------------------------------------

    private void move(MotionEvent e) {
        if (forwardingShade) {
            host.onShadeDragEvent(e);
            return;
        }
        if (view.seekBody != null && downCard != null) {
            view.seekFraction = view.seekBody.fractionAt(e.getX() - shapes.contentLeft(downCard));
            view.scheduleFrame();
            view.invalidate();
            return;
        }
        float dx = e.getX() - downX;
        float dy = e.getY() - downY;
        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
            dragging = true;
            buttons.release();
            sp.press.animateTo(1.0f, Motion.PRESS_UP);
        }
        if (!dragging) {
            return;
        }
        boolean onIsland = downOnPill || downOnBubble;
        if (scene.mode == Mode.EXPANDED && downCard != null) {
            dragStack(dx, dy);
        } else if (scene.mode == Mode.COMPACT && onIsland && dy > touchSlop && Math.abs(dy) > Math.abs(dx)) {
            handOverToShade(e);
        } else if (scene.mode == Mode.COMPACT && onIsland) {
            sp.offsetX.snapTo(Math.signum(dx) * Math.min(m.dp(16.0f), Math.abs(dx) * 0.18f));
            view.invalidate();
            view.notifyHost();
        }
    }

    private void handOverToShade(MotionEvent e) {
        forwardingShade = true;
        sp.press.animateTo(1.0f, Motion.PRESS_UP);
        sp.offsetX.animateTo(0.0f, Motion.RUBBER_RELEASE);
        view.scheduleFrame();
        host.onShadeDragStart(downX, downY, e.getDownTime());
        host.onShadeDragEvent(e);
    }

    /** Rubber-band drag of the open card stack: sideways moves one card, vertical moves the whole stack. */
    private void dragStack(float dx, float dy) {
        if (axis == Axis.UNDECIDED) {
            axis = Math.abs(dx) > Math.abs(dy) ? Axis.HORIZONTAL : Axis.VERTICAL;
        }
        stackDragging = true;
        if (axis == Axis.HORIZONTAL) {
            float pull = rubber(dx, downCard.body.w * 0.55f);
            downCard.dx.snapTo(pull);
            for (Card other : scene.cards) {
                if (other != downCard && !other.leaving) {
                    other.dx.animateTo((0.12f * pull) / (Math.abs(other.order - downCard.order) + 1), 0.25f, 0.8f);
                }
            }
        } else {
            sp.stackDy.snapTo(rubber(dy, m.dp(dy > 0.0f ? 90.0f : 60.0f)));
            sp.stackDx.snapTo(rubber(dx, m.dp(24.0f)));
        }
        view.scheduleFrame();
        view.invalidate();
        view.notifyHost();
    }

    // ---- up / cancel ------------------------------------------------------------------------------------------------

    private void up(MotionEvent e) {
        if (forwardingShade) {
            forwardingShade = false;
            host.onShadeDragEvent(e);
            return;
        }
        if (view.seekBody != null) {
            commitSeek();
            return;
        }
        buttons.release();
        sp.press.animateTo(1.0f, Motion.PRESS_UP);
        if (sp.offsetX.value() != 0.0f) {
            sp.offsetX.animateTo(0.0f, Motion.RUBBER_RELEASE);
        }
        view.scheduleFrame();
        float dx = e.getX() - downX;
        float dy = e.getY() - downY;
        boolean horizontal = Math.abs(dx) > m.dp(SWIPE_MIN_DP) && Math.abs(dx) > Math.abs(dy) * SWIPE_DOMINANCE;
        if (downOnHole) {
            if ((horizontal && dx > 0.0f) || !dragging) {
                host.onRestore();
            }
        } else if (dragging) {
            dragReleased(horizontal, dx, dy);
        } else {
            tap(e.getX(), e.getY());
        }
    }

    /** Commits the media seek that was dragged on a card's progress bar. */
    private void commitSeek() {
        MediaBody body = view.seekBody;
        view.seekBody = null;
        long target = (long) (view.seekFraction * body.item.mediaDuration);
        body.item.mediaPosition = target;
        body.item.mediaPositionUpdateTime = SystemClock.elapsedRealtime();
        if (body.item.mediaSeek != null) {
            body.item.mediaSeek.accept(target);
        }
        view.performHapticFeedback(HAPTIC_SEEK_DONE);
        director.postAutoCollapse();
        view.invalidate();
    }

    private void dragReleased(boolean horizontal, float dx, float dy) {
        if (scene.mode == Mode.COMPACT && horizontal) {
            view.performHapticFeedback(HAPTIC_CONFIRM);
            if (dx < 0.0f) {
                boolean bubbleSwiped = downOnBubble && scene.secondary != null;
                host.onMinimize(bubbleSwiped ? scene.secondary : scene.primary);
            } else if (host.hasMinimized()) {
                host.onRestore();
            } else {
                host.onCycle(-1);
            }
        } else if (scene.mode == Mode.EXPANDED && axis == Axis.VERTICAL && dy < -m.dp(SWIPE_UP_DISMISS_DP)) {
            releaseStack();
            director.collapse();
        } else if (scene.mode == Mode.EXPANDED) {
            releaseStack();
            director.postAutoCollapse();
        }
    }

    private void cancel(MotionEvent e) {
        if (forwardingShade) {
            forwardingShade = false;
            host.onShadeDragEvent(e);
        }
        view.seekBody = null;
        releaseStack();
        buttons.release();
        sp.press.animateTo(1.0f, Motion.PRESS_UP);
        sp.offsetX.animateTo(0.0f, Motion.RUBBER_RELEASE);
        if (scene.mode == Mode.EXPANDED) {
            director.postAutoCollapse();
        }
        view.scheduleFrame();
    }

    /** Lets the dragged cards spring back to their places. */
    private void releaseStack() {
        axis = Axis.UNDECIDED;
        for (Card c : scene.cards) {
            if (c.dx.value() != 0.0f || c.dx.target() != 0.0f) {
                c.dx.animateTo(0.0f, Motion.RUBBER_RELEASE);
            }
        }
        boolean idle = !stackDragging && sp.stackDx.value() == 0.0f && sp.stackDy.value() == 0.0f;
        if (!idle) {
            stackDragging = false;
            sp.stackDx.animateTo(0.0f, Motion.RUBBER_RELEASE);
            sp.stackDy.animateTo(0.0f, Motion.RUBBER_RELEASE);
        }
        view.scheduleFrame();
    }

    private void tap(float x, float y) {
        if (scene.mode == Mode.COMPACT) {
            director.expandFromPill();
            return;
        }
        if (scene.mode != Mode.EXPANDED || downCard == null) {
            return;
        }
        Card c = downCard;
        shapes.card(c, slot);
        Btn b = c.body.hit(x - m.centredLeft(c.body.w), y - slot.top);
        if (b != null) {
            view.performHapticFeedback(HAPTIC_CONFIRM);
            if (b.run != null) {
                b.run.run();
            } else if (b.action != null) {
                host.onItemAction(c.item, b.action);
            }
            director.postAutoCollapse();
            return;
        }
        host.onOpenItem(c.item, new RectF(slot));
        director.collapse();
    }
}
