package com.shumtugle.amalgam;

/**
 * The dark medium behind the glass: a damped wave equation on a coarse height grid.
 * Plain Java, no platform classes, so the same code runs on a desktop test bench.
 */
final class Liquid {
    /** Slope stored in a texel byte: 128 + slope * SLOPE. The shader undoes it. */
    static final float SLOPE = 300f;
    /** Height stored in a texel byte: 128 + height * HEIGHT. */
    static final float HEIGHT = 60f;

    final int cols, rows;
    /** RGBA per cell, bottom row first: slope x, slope y, height, unused. */
    final byte[] texels;
    private float[] cur, prev;

    /** Wave speed squared, in cells per step; well below the 0.5 stability limit. Low means syrup. */
    private static final float SPEED = 0.17f;
    /** Energy kept per step. */
    private static final float KEEP = 0.986f;

    Liquid(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        cur = new float[cols * rows];
        prev = new float[cols * rows];
        texels = new byte[cols * rows * 4];
    }

    /**
     * Pushes the surface down around (u, v), both in [0, 1], v measured from the bottom.
     * Radius is in cells; depth is in height units, roughly 1 for a firm touch.
     */
    void press(float u, float v, float radius, float depth) {
        float cx = u * (cols - 1), cy = v * (rows - 1);
        int r = (int) Math.ceil(radius * 2.5f);
        int x0 = Math.max(1, (int) cx - r), x1 = Math.min(cols - 2, (int) cx + r);
        int y0 = Math.max(1, (int) cy - r), y1 = Math.min(rows - 2, (int) cy + r);
        float inv = 1f / (radius * radius);
        for (int y = y0; y <= y1; y++) {
            float dy = y - cy;
            for (int x = x0; x <= x1; x++) {
                float dx = x - cx;
                float k = (float) Math.exp(-(dx * dx + dy * dy) * inv);
                cur[y * cols + x] -= depth * k;
            }
        }
    }

    /**
     * The highest of a few random cells away from the edges: where waves pile up.
     * Writes its position into out as u, v and returns its height.
     */
    float crest(java.util.Random rnd, int samples, float[] out) {
        float best = -1e9f;
        for (int i = 0; i < samples; i++) {
            int x = 4 + rnd.nextInt(cols - 8), y = 4 + rnd.nextInt(rows - 8);
            float h = cur[y * cols + x];
            if (h > best) {
                best = h;
                out[0] = (float) x / (cols - 1);
                out[1] = (float) y / (rows - 1);
            }
        }
        return best;
    }

    /** One simulation step; the caller keeps the rate fixed. Edges stay still. */
    void step() {
        final int c = cols;
        final float[] a = cur, b = prev;
        for (int y = 1; y < rows - 1; y++) {
            int i = y * c + 1;
            for (int x = 1; x < c - 1; x++, i++) {
                float lap = a[i - 1] + a[i + 1] + a[i - c] + a[i + c] - 4f * a[i];
                b[i] = (2f * a[i] - b[i] + SPEED * lap) * KEEP;
            }
        }
        prev = a;
        cur = b;
    }

    /** Packs slopes and heights into the texel array for upload. */
    void encode() {
        final int c = cols;
        final float[] h = cur;
        final byte[] t = texels;
        for (int y = 0; y < rows; y++) {
            int ym = y > 0 ? y - 1 : y, yp = y < rows - 1 ? y + 1 : y;
            for (int x = 0; x < c; x++) {
                int xm = x > 0 ? x - 1 : x, xp = x < c - 1 ? x + 1 : x;
                float sx = (h[y * c + xp] - h[y * c + xm]) * 0.5f;
                float sy = (h[yp * c + x] - h[ym * c + x]) * 0.5f;
                int o = (y * c + x) * 4;
                t[o] = pack(sx * SLOPE);
                t[o + 1] = pack(sy * SLOPE);
                t[o + 2] = pack(h[y * c + x] * HEIGHT);
                t[o + 3] = (byte) 255;
            }
        }
    }

    private static byte pack(float v) {
        int q = 128 + Math.round(v);
        return (byte) (q < 0 ? 0 : q > 255 ? 255 : q);
    }
}
