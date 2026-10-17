package com.shumtugle.amalgam;

import java.util.ArrayList;
import java.util.Random;

/**
 * A cluster of hopper crystals: square, stepped, hollow boxes that grow out of each other's faces
 * on a cubic lattice. Each hopper is a base slab with concentric square rings on it; the outer ring
 * stands tallest and every inner ring is lower, so the middle is a stepped pit.
 *
 * Geometry comes out as flat triangles, eight floats per vertex:
 * position xyz, normal xyz, film thickness, shade.
 */
final class Crystal {
    static final int FLOATS = 8;
    static final int MAX_HOPPERS = 48;
    static final int MAX_RINGS = 6;
    /** Vertices one hopper can produce: a slab plus four boxes per ring, 36 vertices a box. */
    static final int HOPPER_VERTS = (1 + 4 * MAX_RINGS) * 36;

    static final class Hopper {
        /** Local frame in cluster space: right, up, forward, origin. Origin sits on the base. */
        final float[] f = new float[12];
        float size;
        /** Growth in [0, 1]; geometry scales with an eased version of it. */
        float grown;
        /** Oxide film thickness of the outer ring, in nanometres. */
        float film;
        int rings;
        final float[] jitter = new float[MAX_RINGS];

        float top() {
            return size * (0.18f + 0.75f);
        }

        float cx() { return f[9] + f[3] * top() * 0.5f; }
        float cy() { return f[10] + f[4] * top() * 0.5f; }
        float cz() { return f[11] + f[5] * top() * 0.5f; }
    }

    final ArrayList<Hopper> hoppers = new ArrayList<Hopper>();
    private final Random rnd;
    /** The hopper being grown, or null when the cluster is full. */
    Hopper active;
    /** Bumped every time a hopper finishes, so the renderer knows to rebuild settled geometry. */
    int settledVersion;

    Crystal(long seed) {
        rnd = new Random(seed);
        Hopper root = new Hopper();
        root.f[0] = 1; root.f[4] = 1; root.f[8] = 1;
        root.size = 1f;
        root.film = 60f + rnd.nextFloat() * 40f;
        root.rings = 5;
        fillJitter(root);
        hoppers.add(root);
        active = root;
    }

    /** Hoppers that have finished growing. */
    int settledCount() {
        return hoppers.size() - (active != null ? 1 : 0);
    }

    /**
     * Rebuilds a saved cluster: the same seed gives the same hoppers in the same order,
     * so the count of finished ones and the growth of the last one are enough.
     */
    void restore(int settled, float grown) {
        while (settledCount() < settled && active != null) {
            active.grown = 1f;
            settledVersion++;
            active = hoppers.size() < MAX_HOPPERS ? spawn() : null;
        }
        if (active != null) active.grown = Math.max(0f, Math.min(0.999f, grown));
    }

    /** Sum of size squared times growth: how much crystal there is. */
    float mass() {
        float m = 0;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            m += h.size * h.size * ease(h.grown);
        }
        return m;
    }

    /** Energy needed to grow a hopper of this size from nothing. */
    private float cost(float size) {
        // later hoppers cost more, so the cluster fills in minutes of play, not seconds
        return (0.5f + 1.3f * size * size) * (1f + hoppers.size() * 0.25f);
    }

    /**
     * Feeds energy into the active hopper. Returns what was not used: some is left over
     * when a hopper finishes mid-feed, and all of it when the cluster is full.
     */
    float feed(float energy) {
        if (active == null) return energy;
        float need = (1f - active.grown) * cost(active.size);
        if (energy < need) {
            active.grown += energy / cost(active.size);
            return 0f;
        }
        active.grown = 1f;
        settledVersion++;
        active = hoppers.size() < MAX_HOPPERS ? spawn() : null;
        return energy - need;
    }

    private void fillJitter(Hopper h) {
        for (int k = 0; k < MAX_RINGS; k++) h.jitter[k] = (rnd.nextFloat() - 0.5f) * 0.35f;
    }

    /** Places a new hopper on a face of an existing one. Falls back to a smaller one if crowded. */
    private Hopper spawn() {
        for (int tries = 0; tries < 24; tries++) {
            // Recent hoppers are likelier parents, so the cluster reaches outwards instead of thickening.
            int n = hoppers.size();
            int pi = n - 1 - (int) (Math.abs(rnd.nextGaussian()) * Math.max(2, n * 0.35f));
            if (pi < 0) pi = rnd.nextInt(n);
            Hopper p = hoppers.get(pi);
            // Near the parent's size, so generations do not shrink into gravel.
            float s = p.size * (0.74f + 0.34f * rnd.nextFloat()) * (tries < 16 ? 1f : 0.8f);
            s = Math.max(0.4f, Math.min(1.05f, s));

            // Child up is one of the parent's six axes; child right is any axis perpendicular to it.
            int face = rnd.nextInt(6);
            int axis = face >> 1;
            float sign = (face & 1) == 0 ? 1f : -1f;
            int sideAxis = (axis + 1 + rnd.nextInt(2)) % 3;
            float sideSign = rnd.nextBoolean() ? 1f : -1f;

            Hopper h = new Hopper();
            for (int i = 0; i < 3; i++) {
                h.f[3 + i] = p.f[axis * 3 + i] * sign;
                h.f[i] = p.f[sideAxis * 3 + i] * sideSign;
            }
            // forward = right x up, keeps the frame orthonormal
            h.f[6] = h.f[1] * h.f[5] - h.f[2] * h.f[4];
            h.f[7] = h.f[2] * h.f[3] - h.f[0] * h.f[5];
            h.f[8] = h.f[0] * h.f[4] - h.f[1] * h.f[3];

            // Where on the parent the child stands, in the parent's frame.
            float lr = (rnd.nextFloat() * 2f - 1f) * 0.75f, lf = (rnd.nextFloat() * 2f - 1f) * 0.75f;
            float ox, oy, oz;
            if (axis == 1) {
                ox = lr * p.size; oz = lf * p.size; oy = sign > 0 ? p.top() : 0f;
            } else if (axis == 0) {
                ox = sign * p.size; oy = (0.15f + 0.7f * rnd.nextFloat()) * p.top(); oz = lf * p.size;
            } else {
                oz = sign * p.size; oy = (0.15f + 0.7f * rnd.nextFloat()) * p.top(); ox = lr * p.size;
            }
            for (int i = 0; i < 3; i++)
                h.f[9 + i] = p.f[9 + i] + p.f[i] * ox + p.f[3 + i] * oy + p.f[6 + i] * oz;

            h.size = s;
            h.rings = 3 + rnd.nextInt(MAX_RINGS - 2);
            h.film = p.film + 25f + rnd.nextFloat() * 70f;
            if (h.film > 300f) h.film -= 200f;
            fillJitter(h);

            if (crowded(h, p)) continue;
            hoppers.add(h);
            return h;
        }
        return null;
    }

    private boolean crowded(Hopper h, Hopper parent) {
        float hx = h.cx(), hy = h.cy(), hz = h.cz();
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper q = hoppers.get(i);
            if (q == parent) continue;
            float dx = q.cx() - hx, dy = q.cy() - hy, dz = q.cz() - hz;
            float lim = (q.size + h.size) * 0.9f;
            if (dx * dx + dy * dy + dz * dz < lim * lim) return true;
        }
        return false;
    }

    /**
     * Extent of the cluster around (cx, cy, cz), counting growth: out[0] is the radius across
     * the vertical axis, which the slow spin never changes; out[1] the half height.
     */
    void reach(float cx, float cy, float cz, float[] out) {
        float across = 0.6f, up = 0.6f;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            float g = ease(h.grown);
            if (g <= 0) continue;
            float dx = h.cx() - cx, dy = h.cy() - cy, dz = h.cz() - cz;
            float r = h.size * g * 1.15f;
            across = Math.max(across, (float) Math.sqrt(dx * dx + dz * dz) + r);
            up = Math.max(up, Math.abs(dy) + r);
        }
        out[0] = across;
        out[1] = up;
    }

    /** Mass-weighted middle of the cluster, written into out[0..2]. */
    void middle(float[] out) {
        float sx = 0, sy = 0, sz = 0, sw = 0;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            float w = h.size * h.size * ease(h.grown) + 1e-4f;
            sx += h.cx() * w; sy += h.cy() * w; sz += h.cz() * w; sw += w;
        }
        out[0] = sx / sw; out[1] = sy / sw; out[2] = sz / sw;
    }

    static float ease(float g) {
        float a = 1f - (g < 0 ? 0 : g > 1 ? 1 : g);
        return 1f - a * a * a;
    }

    /** Writes every finished hopper; returns the vertex count. */
    int buildSettled(float[] out) {
        int v = 0;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            if (h != active) v = emitHopper(h, out, v);
        }
        return v;
    }

    /** Writes every hopper, growing or not; returns the vertex count. */
    int buildAll(float[] out) {
        int v = 0;
        for (int i = 0; i < hoppers.size(); i++) v = emitHopper(hoppers.get(i), out, v);
        return v;
    }

    /** Writes only the hopper still growing; returns the vertex count. */
    int buildActive(float[] out) {
        return active == null ? 0 : emitHopper(active, out, 0);
    }

    private int emitHopper(Hopper h, float[] out, int v) {
        float g = ease(h.grown);
        if (g <= 0.001f) return v;
        float s = h.size * g;
        float base = s * 0.18f;
        float t = s / h.rings;
        float step = t * 0.75f;
        v = box(out, v, h.f, 0, base * 0.5f, 0, s, base * 0.5f, s, h.film - 35f, 0.55f);
        for (int k = 0; k < h.rings; k++) {
            float a = s - k * t;
            float tall = base + (h.rings - k) * step * (1f + h.jitter[k]);
            float hy = tall * 0.5f;
            float film = h.film + k * 24f;
            float shade = 1f - k * 0.11f;
            float w = t * 0.5f;
            v = box(out, v, h.f, 0, hy, a - w, a, hy, w, film, shade);
            v = box(out, v, h.f, 0, hy, -(a - w), a, hy, w, film, shade);
            v = box(out, v, h.f, a - w, hy, 0, w, hy, a - t, film, shade);
            v = box(out, v, h.f, -(a - w), hy, 0, w, hy, a - t, film, shade);
        }
        return v;
    }

    /** A box centred at (x, y, z) in the frame, with half extents along right, up and forward. */
    private static int box(float[] out, int v, float[] f, float x, float y, float z,
                           float hx, float hy, float hz, float film, float shade) {
        float[] c = {
                f[9] + f[0] * x + f[3] * y + f[6] * z,
                f[10] + f[1] * x + f[4] * y + f[7] * z,
                f[11] + f[2] * x + f[5] * y + f[8] * z};
        float[] half = {hx, hy, hz};
        for (int axis = 0; axis < 3; axis++) {
            int ua = (axis + 1) % 3, va = (axis + 2) % 3;
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                float nx = f[axis * 3] * sgn, ny = f[axis * 3 + 1] * sgn, nz = f[axis * 3 + 2] * sgn;
                float px = c[0] + nx * half[axis], py = c[1] + ny * half[axis], pz = c[2] + nz * half[axis];
                float ux = f[ua * 3] * half[ua], uy = f[ua * 3 + 1] * half[ua], uz = f[ua * 3 + 2] * half[ua];
                float wx = f[va * 3] * half[va], wy = f[va * 3 + 1] * half[va], wz = f[va * 3 + 2] * half[va];
                v = vert(out, v, px - ux - wx, py - uy - wy, pz - uz - wz, nx, ny, nz, film, shade);
                v = vert(out, v, px + ux - wx, py + uy - wy, pz + uz - wz, nx, ny, nz, film, shade);
                v = vert(out, v, px + ux + wx, py + uy + wy, pz + uz + wz, nx, ny, nz, film, shade);
                v = vert(out, v, px - ux - wx, py - uy - wy, pz - uz - wz, nx, ny, nz, film, shade);
                v = vert(out, v, px + ux + wx, py + uy + wy, pz + uz + wz, nx, ny, nz, film, shade);
                v = vert(out, v, px - ux + wx, py - uy + wy, pz - uz + wz, nx, ny, nz, film, shade);
            }
        }
        return v;
    }

    private static int vert(float[] out, int v, float x, float y, float z,
                            float nx, float ny, float nz, float film, float shade) {
        int o = v * FLOATS;
        out[o] = x; out[o + 1] = y; out[o + 2] = z;
        out[o + 3] = nx; out[o + 4] = ny; out[o + 5] = nz;
        out[o + 6] = film; out[o + 7] = shade;
        return v + 1;
    }
}
