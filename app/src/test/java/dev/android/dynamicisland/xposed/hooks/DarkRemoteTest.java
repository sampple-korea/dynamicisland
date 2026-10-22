package dev.android.dynamicisland.xposed.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.robolectric.RuntimeEnvironment;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class DarkRemoteTest {
    /** Text colours as the apps hand them over: Google's light-theme greys, brand colours, hard black, see-through ones. */
    private static final int[] PALETTE = {
            0xFF202124, 0xFF3C4043, 0xFF5F6368, 0xFF70757A, 0xFF9AA0A6, 0xFFBDC1C6, 0xFFE8EAED, 0xFFFFFFFF,
            0xFF000000, 0x99000000, 0x80202124, 0xCCFFFFFF, 0x66FFFFFF,
            0xFF1A73E8, 0xFF8AB4F8, 0xFFD93025, 0xFFF28B82, 0xFF188038, 0xFF81C995, 0xFFE37400, 0xFFFBBC04,
            0xFF0000FF, 0xFF990000, 0xFF003366, 0xFF4A148C, 0xFFB71C1C,
    };

    @Test
    public void darkTextBecomesLightWithItsTransparency() {
        int lifted = DarkRemote.lift(Color.argb(0x99, 0x20, 0x20, 0x20));
        assertEquals(0x99, Color.alpha(lifted));
        assertTrue(Color.red(lifted) > 0xE0);
    }

    @Test
    public void readableTextStaysAsItIs() {
        assertEquals(0xFFEEEEEE, DarkRemote.lift(0xFFEEEEEE));
        assertEquals(0xFF8AB4F8, DarkRemote.lift(0xFF8AB4F8));
        // a mid grey that is readable on the dark card is not turned white just because it is "dark-ish"
        assertEquals(0xFF9AA0A6, DarkRemote.lift(0xFF9AA0A6));
    }

    @Test
    public void everyColourEndsUpReadableAndKeepsItsHue() {
        for (int color : PALETTE) {
            int lifted = DarkRemote.lift(color);
            assertEquals(Integer.toHexString(color), Color.alpha(color), Color.alpha(lifted));
            float after = DarkRemote.contrast(lifted, DarkRemote.CARD_BACKGROUND);
            // see-through white can never get past its own transparency: it just may not get worse
            if (Color.alpha(color) == 0xFF || DarkRemote.contrast(color, DarkRemote.CARD_BACKGROUND) < DarkRemote.MIN_CONTRAST) {
                assertTrue(Integer.toHexString(color) + " -> " + Integer.toHexString(lifted) + " contrast " + after,
                        after >= DarkRemote.MIN_CONTRAST || Color.alpha(color) < 0xFF);
            }
            float[] before = new float[3];
            float[] now = new float[3];
            Color.colorToHSV(color, before);
            Color.colorToHSV(lifted, now);
            if (before[1] > 0.4f && now[1] > 0.1f && before[2] > 0.2f) {
                float diff = Math.abs(before[0] - now[0]);
                assertTrue("hue of " + Integer.toHexString(color), Math.min(diff, 360.0f - diff) < 6.0f);
            }
        }
    }

    @Test
    public void invisibleTextIsLeftAlone() {
        assertEquals(0x00000000, DarkRemote.lift(0x00000000));
    }

    @Test
    public void aWholeViewTreeIsLifted() {
        Application app = RuntimeEnvironment.getApplication();
        LinearLayout root = new LinearLayout(app);
        int[] before = {0xFF202124, 0xFF5F6368, 0xFFFFFFFF};
        for (int color : before) {
            TextView t = new TextView(app);
            t.setTextColor(color);
            root.addView(t);
        }
        DarkRemote.lighten(root);
        for (int i = 0; i < before.length; i++) {
            int now = ((TextView) root.getChildAt(i)).getCurrentTextColor();
            assertTrue(DarkRemote.contrast(now, DarkRemote.CARD_BACKGROUND) >= DarkRemote.MIN_CONTRAST);
        }
        assertEquals(0xFFFFFFFF, ((TextView) root.getChildAt(2)).getCurrentTextColor());
    }

    /** A picture of every palette colour before (left) and after (right) on three card backdrops. */
    @Test
    public void renderContrastSheet() throws Exception {
        int[] backdrops = {DarkRemote.CARD_BACKGROUND, 0xFF101012, 0xFF3A3A40};
        int rowH = 30;
        int half = 150;
        Bitmap out = Bitmap.createBitmap(half * 2 * backdrops.length, rowH * PALETTE.length, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint fill = new Paint();
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextSize(18.0f);
        for (int b = 0; b < backdrops.length; b++) {
            fill.setColor(backdrops[b]);
            canvas.drawRect(b * half * 2, 0, (b + 1) * half * 2, out.getHeight(), fill);
            fill.setColor(0x33FFFFFF);
            canvas.drawRect((b * half * 2) + half - 1, 0, (b * half * 2) + half, out.getHeight(), fill);
        }
        for (int i = 0; i < PALETTE.length; i++) {
            for (int b = 0; b < backdrops.length; b++) {
                int x = b * half * 2;
                text.setColor(PALETTE[i]);
                canvas.drawText("8:00 PM 21%", x + 6, (i * rowH) + 21, text);
                text.setColor(DarkRemote.lift(PALETTE[i], backdrops[b]));
                canvas.drawText("8:00 PM 21%", x + half + 6, (i * rowH) + 21, text);
            }
        }
        File dir = new File("build/render");
        dir.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(dir, "dark_remote_contrast.png"))) {
            out.compress(Bitmap.CompressFormat.PNG, 100, fo);
        }
    }
}
