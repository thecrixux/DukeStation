package com.sbro.emucorer.translate;

/** Pure geometry, in SurfaceView pixels. Invalid/stale renderer bounds fall back to the full surface. */
public final class CaptureArea {
    private CaptureArea() {}
    public static int[] calculate(int width, int height, float[] display, int region) {
        if (width < 2 || height < 2) throw new IllegalArgumentException("Surface too small");
        int left = 0, top = 0, right = width, bottom = height;
        if (display != null && display.length >= 4 && Float.isFinite(display[0]) && Float.isFinite(display[1])
                && Float.isFinite(display[2]) && Float.isFinite(display[3])) {
            int l = Math.max(0, Math.min(width, Math.round(display[0])));
            int t = Math.max(0, Math.min(height, Math.round(display[1])));
            int r = Math.max(0, Math.min(width, Math.round(display[2])));
            int b = Math.max(0, Math.min(height, Math.round(display[3])));
            if (r-l >= 2 && b-t >= 2) { left=l; top=t; right=r; bottom=b; }
        }
        int h = bottom-top;
        if (region == 1) top += h/2;
        else if (region == 2) top += h*2/3;
        else if (region == 3) bottom = top + Math.max(1,h/2);
        return new int[]{left,top,right,bottom};
    }
}
