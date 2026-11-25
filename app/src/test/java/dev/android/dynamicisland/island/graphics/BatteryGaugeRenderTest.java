package dev.android.dynamicisland.island.graphics;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import dev.android.dynamicisland.testutil.RenderCapture;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The charging ring at pill size: bolt, shield (protected), arrows (pass-through) and the bare number. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35)
public class BatteryGaugeRenderTest {
    @Test
    public void everyCentreDrawsInsideItsRing() throws Exception {
        int[] centres = {BatteryGauge.CENTRE_BOLT, BatteryGauge.CENTRE_SHIELD, BatteryGauge.CENTRE_BYPASS,
                BatteryGauge.CENTRE_NUMBER, BatteryGauge.CENTRE_NUMBER};
        int[] levels = {71, 80, 64, 71, 100};
        int cell = 96;
        Bitmap sheet = Bitmap.createBitmap(cell * centres.length, cell, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sheet);
        canvas.drawColor(Color.BLACK);
        BatteryGauge gauge = new BatteryGauge();
        for (int i = 0; i < centres.length; i++) {
            gauge.draw(canvas, cell * i + cell / 2f, cell / 2f, 66, levels[i], 0xFF30D158, 1f, centres[i]);
        }
        RenderCapture.write(sheet, new File("build/renders/battery-gauge-centres.png"));
        for (int i = 0; i < centres.length; i++) {
            int lit = 0;
            for (int y = cell / 2 - 14; y < cell / 2 + 14; y++) {
                for (int x = cell * i + cell / 2 - 14; x < cell * i + cell / 2 + 14; x++) {
                    if (Color.green(sheet.getPixel(x, y)) > 120) lit++;
                }
            }
            assertTrue("centre " + i + " draws something in the middle: " + lit, lit > 40);
        }
    }
}
