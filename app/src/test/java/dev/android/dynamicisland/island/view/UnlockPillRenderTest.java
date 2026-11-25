package dev.android.dynamicisland.island.view;

import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import dev.android.dynamicisland.island.graphics.GlyphIcon;
import dev.android.dynamicisland.island.model.IslandItem;
import dev.android.dynamicisland.testutil.RenderCapture;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The unlock pill with its label and without words: the method's glyph on the left, a green open padlock on the right. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "mdpi")
public class UnlockPillRenderTest {
    private static IslandItem unlock(boolean words) {
        IslandItem it = new IslandItem("event:unlock");
        it.kind = IslandItem.Kind.EVENT;
        it.eventLayout = IslandItem.EventLayout.STATUS;
        it.title = "Unlocked with fingerprint";
        it.icon = GlyphIcon.of(GlyphIcon.Kind.FINGERPRINT);
        it.accent = 0xFF30D158;
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = "Fingerprint";
        if (!words) {
            it.trailing = IslandItem.Trailing.ICON;
            it.trailingIcon = GlyphIcon.of(GlyphIcon.Kind.LOCK_OPEN);
        }
        return it;
    }

    @Test
    public void theOpenPadlockIsGreenOnTheRightAndThePillIsSmaller() throws Exception {
        GlyphIcon.setPlaybackFrozen(true);
        try {
            IslandView view = new IslandView(RuntimeEnvironment.getApplication(), IslandHost.NONE);
            view.setGeometry(360, 180, 35, 0, false);
            float scale = 3f;
            Bitmap sheet = Bitmap.createBitmap((int) (360 * scale), (int) (100 * scale), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(sheet);
            canvas.drawColor(0xFF7A4A9A);
            canvas.scale(scale, scale);
            float h = org.robolectric.util.ReflectionHelpers.getField(org.robolectric.util.ReflectionHelpers.getField(view, "metrics"), "pillH");
            android.graphics.Paint black = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            black.setColor(Color.BLACK);
            RectF iconShape = null;
            float[] widths = new float[2];
            for (int row = 0; row < 2; row++) {
                CompactContent content = new CompactContent(view, unlock(row == 0));
                float w = content.desiredWidth();
                widths[row] = w;
                RectF shape = new RectF(180 - w / 2, 10 + row * 45, 180 + w / 2, 10 + row * 45 + h);
                canvas.drawRoundRect(shape, h / 2, h / 2, black);
                content.draw(canvas, shape, 1f, 0);
                if (row == 1) iconShape = shape;
            }
            RenderCapture.write(sheet, new File("build/renders/unlock-pill.png"));
            assertTrue(widths[1] < widths[0]);
            int green = 0;
            int mid = (int) ((iconShape.top + h / 2) * scale);
            for (int y = mid - 20; y < mid + 20; y++) {
                for (int x = (int) (iconShape.centerX() * scale); x < (int) (iconShape.right * scale); x++) {
                    int p = sheet.getPixel(x, y);
                    if (Color.green(p) > 150 && Color.red(p) < 120 && Color.blue(p) < 130) green++;
                }
            }
            assertTrue("a green padlock on the right half: " + green, green > 40);
        } finally {
            GlyphIcon.setPlaybackFrozen(false);
        }
    }
}
