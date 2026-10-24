package com.sbro.emucorer.translate;

/** Display-independent window bounds. Stored positions are fractions of the available travel. */
final class TranslationWindowGeometry {
    static final int DEFAULT_WIDTH = 440, DEFAULT_HEIGHT = 220, DEFAULT_OPACITY = 93;
    static int width(int screen, float density, float requestedDp) {
        return size(screen, density, requestedDp, 240, DEFAULT_WIDTH);
    }
    static int height(int screen, float density, float requestedDp) {
        return size(screen, density, requestedDp, 120, DEFAULT_HEIGHT);
    }
    private static int size(int screen, float density, float requestedDp, int minimum, int fallback) {
        density = Float.isFinite(density) && density > 0 ? density : 1;
        float value = Float.isFinite(requestedDp) ? requestedDp : fallback;
        int available = Math.max(1, screen - 2 * margin(screen, density));
        return Math.max(1, Math.min(available, Math.round(Math.max(minimum, Math.min(800, value)) * density)));
    }
    static int margin(int screen, float density) {
        return Math.min(Math.max(0, (screen - 1) / 2), Math.max(0, Math.round(8 * density)));
    }
    static float position(int screen, int window, float density, float fraction) {
        int margin = margin(screen, density);
        int travel = Math.max(0, screen - window - 2 * margin);
        return margin + normalized(fraction) * travel;
    }
    static float fraction(int screen, int window, float density, float position) {
        int margin = margin(screen, density);
        int travel = Math.max(0, screen - window - 2 * margin);
        return travel == 0 ? .5f : normalized((position - margin) / travel);
    }
    static float normalized(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : .5f;
    }
    static int opacity(int percent) { return Math.max(20, Math.min(100, percent)); }
}
