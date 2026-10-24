package com.sbro.emucorer.translate;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureAreaTest {
    @Test public void japaneseDialogRegionUsesGameNotLetterbox() {
        assertArrayEquals(new int[]{240,480,1680,720}, CaptureArea.calculate(1920,1080,new float[]{240,0,1680,720},2));
    }
    @Test public void invalidBoundsAfterRotationFallBackToSurface() {
        assertArrayEquals(new int[]{0,0,720,1280},CaptureArea.calculate(720,1280,new float[]{900,0,1800,1080},0));
        assertArrayEquals(new int[]{0,0,720,1280},CaptureArea.calculate(720,1280,new float[]{Float.NaN,0,600,400},0));
    }
    @Test public void cropStaysInsideSurface() {
        assertArrayEquals(new int[]{0,50,100,100},CaptureArea.calculate(100,100,new float[]{-30,-20,200,300},1));
        for(int region=0;region<4;region++) {
            int[] r=CaptureArea.calculate(2,2,null,region);
            assertTrue(r[2]>r[0]);assertTrue(r[3]>r[1]);assertTrue(r[3]<=2);
        }
    }
    @Test public void topHalfHonoursLetterboxOffset() {
        assertArrayEquals(new int[]{0,200,1280,560},CaptureArea.calculate(1280,1120,new float[]{0,200,1280,920},3));
    }
}
