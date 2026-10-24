package com.sbro.emucorer.translate;

import org.junit.Test;
import static org.junit.Assert.*;

public class TranslationWindowGeometryTest {
    @Test public void fitsSmallScreensAndClampsHugeRequestedSize() {
        assertEquals(304, TranslationWindowGeometry.width(320, 1, 800));
        assertEquals(184, TranslationWindowGeometry.height(200, 1, 800));
        assertEquals(1, TranslationWindowGeometry.width(1, 3, 440));
        assertEquals(1, TranslationWindowGeometry.height(0, 2, 220));
    }
    @Test public void storedSizeScalesWithDensityAndHandlesInvalidPreferences() {
        assertEquals(880, TranslationWindowGeometry.width(1080, 2, 440));
        assertEquals(440, TranslationWindowGeometry.height(1080, 2, Float.NaN));
        assertEquals(240, TranslationWindowGeometry.width(1080, 1, -20));
        assertEquals(120, TranslationWindowGeometry.height(1080, 1, -20));
        assertEquals(440, TranslationWindowGeometry.width(1080, Float.NaN, Float.NaN));
    }
    @Test public void dragCannotMoveHeaderOrResizeHandleOffScreen() {
        assertEquals(8f, TranslationWindowGeometry.position(900, 400, 1, -4), 0);
        assertEquals(492f, TranslationWindowGeometry.position(900, 400, 1, 4), 0);
        assertEquals(250f, TranslationWindowGeometry.position(900, 400, 1, Float.NaN), 0);
        assertEquals(0f, TranslationWindowGeometry.fraction(900, 400, 1, -100), 0);
        assertEquals(1f, TranslationWindowGeometry.fraction(900, 400, 1, 2000), 0);
        assertEquals(.5f, TranslationWindowGeometry.fraction(300, 284, 1, 8), 0);
    }
    @Test public void positionRoundTripAndRotationKeepWindowInsideScreen() {
        for (int[] display : new int[][]{{1080, 2400}, {2400,1080}, {320,240}}) {
            int width = TranslationWindowGeometry.width(display[0], 2, 440);
            int height = TranslationWindowGeometry.height(display[1], 2, 220);
            for (float fraction : new float[]{0, .25f, .5f, 1}) {
                float x = TranslationWindowGeometry.position(display[0], width, 2, fraction);
                float y = TranslationWindowGeometry.position(display[1], height, 2, fraction);
                assertTrue(x >= 0 && x + width <= display[0]);
                assertTrue(y >= 0 && y + height <= display[1]);
                if (display[0] - width > 32)
                    assertEquals(fraction, TranslationWindowGeometry.fraction(display[0], width, 2, x), .0001f);
            }
        }
    }
    @Test public void opacityAlwaysAllowsRecoveringTheWindow() {
        assertEquals(20, TranslationWindowGeometry.opacity(-1));
        assertEquals(100, TranslationWindowGeometry.opacity(300));
        assertEquals(65, TranslationWindowGeometry.opacity(65));
    }
}
