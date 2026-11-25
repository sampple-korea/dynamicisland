package dev.android.dynamicisland.island.graphics;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Static circular level gauge shared by compact and expanded battery presentations. */
public final class BatteryGauge {
    /** Nothing in the middle of the ring. */
    public static final int CENTRE_NONE = 0;
    /** "71%" in white, for the large gauge of a card. */
    public static final int CENTRE_PERCENT = 1;
    /** The lightning bolt of a charging battery. */
    public static final int CENTRE_BOLT = 2;
    /** A shield with a tick: battery protection holds the charge. */
    public static final int CENTRE_SHIELD = 3;
    /** The ring becomes two arrows around the bolt: the charger powers the phone and the battery rests. */
    public static final int CENTRE_BYPASS = 4;
    /** The bare level ("71"), large enough to read in the pill. */
    public static final int CENTRE_NUMBER = 5;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path path = new Path();
    private static final android.graphics.PorterDuffXfermode CUT_OUT =
            new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT);

    public void setColorFilter(ColorFilter filter) { stroke.setColorFilter(filter); text.setColorFilter(filter); }

    /** The centre of a charging gauge for a {@code ChargingState} value. */
    public static int centreFor(int chargingState, boolean numberInsteadOfBolt) {
        switch (chargingState) {
            case 1: return CENTRE_SHIELD;
            case 2: return CENTRE_BYPASS;
            default: return numberInsteadOfBolt ? CENTRE_NUMBER : CENTRE_BOLT;
        }
    }

    public void draw(Canvas canvas, float cx, float cy, float diameter, int percent, int colour,
                     float alpha, boolean number, boolean bolt) {
        draw(canvas, cx, cy, diameter, percent, colour, alpha, number ? CENTRE_PERCENT : bolt ? CENTRE_BOLT : CENTRE_NONE);
    }

    public void draw(Canvas canvas, float cx, float cy, float diameter, int percent, int colour, float alpha, int centre) {
        if (diameter <= 0) return;
        float thickness = Math.max(1.5f, diameter * .08f);
        float radius = (diameter - thickness) / 2f;
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(thickness);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        if (centre == CENTRE_BYPASS) {
            bypassArrows(canvas, cx, cy, radius, thickness, Colors.withAlpha(colour, alpha));
        } else {
            stroke.setColor(Colors.withAlpha(colour, alpha * .19f));
            canvas.drawCircle(cx, cy, radius, stroke);
            if (percent > 0) {
                stroke.setColor(Colors.withAlpha(colour, alpha));
                canvas.drawArc(bounds, -90, Math.min(100, percent) * 3.6f, false, stroke);
            }
        }
        text.setStyle(Paint.Style.FILL);
        text.setColor(Colors.withAlpha(centre == CENTRE_PERCENT ? Colors.WHITE : colour, alpha));
        switch (centre) {
            case CENTRE_PERCENT:
                centredText(canvas, percent >= 0 ? percent + "%" : "—", cx, cy, diameter * .28f, 600);
                break;
            case CENTRE_NUMBER:
                String value = percent >= 0 ? String.valueOf(Math.min(100, percent)) : "—";
                centredText(canvas, value, cx, cy, diameter * (value.length() >= 3 ? .34f : .44f), 700);
                break;
            case CENTRE_BOLT:
            case CENTRE_BYPASS:
                Glyphs.draw(canvas, Glyphs.BOLT, cx, cy, diameter * (centre == CENTRE_BYPASS ? .42f : .5f), text);
                break;
            case CENTRE_SHIELD:
                shield(canvas, cx, cy, diameter * .5f, Colors.withAlpha(colour, alpha));
                break;
            default:
                break;
        }
    }

    private void centredText(Canvas canvas, String value, float cx, float cy, float size, int weight) {
        text.setTypeface(Fonts.of(weight));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size);
        Paint.FontMetrics fm = text.getFontMetrics();
        canvas.drawText(value, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    /** A filled shield with a tick cut out of it, centred on (cx, cy) in a square of {@code size}. */
    private void shield(Canvas canvas, float cx, float cy, float size, int colour) {
        float w = size * .78f;
        float top = cy - size * .48f;
        float bottom = cy + size * .5f;
        path.rewind();
        path.moveTo(cx, top);
        path.cubicTo(cx + w * .3f, top + size * .1f, cx + w * .5f, top + size * .12f, cx + w * .5f, top + size * .12f);
        path.lineTo(cx + w * .5f, cy + size * .02f);
        path.cubicTo(cx + w * .5f, cy + size * .26f, cx + w * .2f, bottom - size * .1f, cx, bottom);
        path.cubicTo(cx - w * .2f, bottom - size * .1f, cx - w * .5f, cy + size * .26f, cx - w * .5f, cy + size * .02f);
        path.lineTo(cx - w * .5f, top + size * .12f);
        path.cubicTo(cx - w * .5f, top + size * .12f, cx - w * .3f, top + size * .1f, cx, top);
        path.close();
        text.setStyle(Paint.Style.FILL);
        text.setColor(colour);
        int layer = canvas.saveLayer(cx - size, cy - size, cx + size, cy + size, null);
        canvas.drawPath(path, text);
        Paint cut = stroke;
        int keepColour = cut.getColor();
        float keepWidth = cut.getStrokeWidth();
        cut.setXfermode(CUT_OUT);
        cut.setStyle(Paint.Style.STROKE);
        cut.setStrokeWidth(size * .13f);
        cut.setColor(0xFF000000);
        path.rewind();
        path.moveTo(cx - size * .2f, cy + size * .02f);
        path.lineTo(cx - size * .04f, cy + size * .18f);
        path.lineTo(cx + size * .22f, cy - size * .14f);
        canvas.drawPath(path, cut);
        cut.setXfermode(null);
        cut.setColor(keepColour);
        cut.setStrokeWidth(keepWidth);
        canvas.restoreToCount(layer);
    }

    /** Two arrows chasing each other round the ring, in place of the level arc. */
    private void bypassArrows(Canvas canvas, float cx, float cy, float radius, float thickness, int colour) {
        stroke.setColor(colour);
        for (int i = 0; i < 2; i++) {
            float start = -60 + i * 180;
            float sweep = 120;
            canvas.drawArc(bounds, start, sweep, false, stroke);
            double end = Math.toRadians(start + sweep);
            float ex = cx + (float) Math.cos(end) * radius;
            float ey = cy + (float) Math.sin(end) * radius;
            // the head points along the circle, the way the arc runs (clockwise)
            float tx = (float) -Math.sin(end);
            float ty = (float) Math.cos(end);
            float nx = (float) Math.cos(end);
            float ny = (float) Math.sin(end);
            float head = thickness * 2.1f;
            path.rewind();
            path.moveTo(ex + tx * head * .9f, ey + ty * head * .9f);
            path.lineTo(ex + nx * head - tx * head * .25f, ey + ny * head - ty * head * .25f);
            path.lineTo(ex - nx * head - tx * head * .25f, ey - ny * head - ty * head * .25f);
            path.close();
            text.setStyle(Paint.Style.FILL);
            text.setColor(colour);
            canvas.drawPath(path, text);
        }
    }
}
