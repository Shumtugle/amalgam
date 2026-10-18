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
    static final int HOPPER_VERTS = (3 + 4 * MAX_RINGS) * 36;

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
        /** How far it has gone under the black surface, 0 to 1. */
        float sink;
        /** Whether it is meant to be under; set by a touch, cleared after half a turn. */
        boolean under;
        /** The cluster's turn when it went under; it comes back up half a turn later. */
        float underSpin;
        /** Cut away with half of the world: gone for good. */
        boolean lost;
        /** How many hoppers there were when it was cut away, so a saved game can replay it; -1 if never. */
        int lostAt = -1;

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
    /**
     * Qubits seated so far. Qubit i sits in the pit of hopper i, and its colour is its place
     * in the spectrum: the first is red, the last of a full cycle violet.
     */
    int qubits;
    /** Colour of each seated qubit: its place in the spectrum when it arrived, or WHITE once fused. */
    final float[] hues = new float[MAX_HOPPERS];
    /** For each qubit, the qubit it is entangled with, or -1. Qubit i sits in hopper i. */
    final int[] partner = new int[MAX_HOPPERS];

    /** Links two seated qubits: they now share a fate. */
    void entangle(int a, int b) {
        if (a == b || a < 0 || b < 0 || a >= qubits || b >= qubits) return;
        partner[a] = b;
        partner[b] = a;
        settledVersion++;
    }

    private void untangle(int a) {
        int b = partner[a];
        if (b >= 0) partner[b] = -1;
        partner[a] = -1;
    }

    /** Hue of a qubit whose hopper was cut away: it takes up its place and nothing else. */
    static final float VOID = -1f;

    /** Whether qubit i is real: seated, and in a hopper still there. */
    boolean realQubit(int i) {
        return i < qubits && hues[i] >= 0f && !hoppers.get(i).lost;
    }

    /**
     * Cuts away hopper i: it and its qubit are gone, and its partner is set free. The growing
     * hopper is never cut; it is still being born.
     */
    void cutAway(int i) {
        Hopper h = hoppers.get(i);
        if (h == active || h.lost) return;
        h.lost = true;
        h.lostAt = hoppers.size();
        h.under = false;
        h.sink = 0f;
        if (i < qubits) {
            untangle(i);
            hues[i] = VOID;
        }
        settledVersion++;
    }

    /** Hue of a qubit fused from several: the spectrum folded back into white light. */
    static final float WHITE = 2f;

    Crystal(long seed) {
        rnd = new Random(seed);
        for (int i = 0; i < MAX_HOPPERS; i++) partner[i] = -1;
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
    void restore(int settled, float grown, float[] seatedHues, int[] partners, int[] lostAt) {
        while (settledCount() < settled && active != null) {
            // hoppers cut away go at the same point of growth as they did in play
            for (int k = 0; k < hoppers.size() && k < lostAt.length; k++)
                if (lostAt[k] >= 0 && lostAt[k] <= hoppers.size() && !hoppers.get(k).lost && hoppers.get(k) != active) {
                    hoppers.get(k).lost = true;
                    hoppers.get(k).lostAt = lostAt[k];
                }
            active.grown = 1f;
            settledVersion++;
            active = hoppers.size() < MAX_HOPPERS ? spawn() : null;
        }
        if (active != null) active.grown = Math.max(0f, Math.min(0.999f, grown));
        for (int k = 0; k < hoppers.size() && k < lostAt.length; k++)
            if (lostAt[k] >= 0 && hoppers.get(k) != active) {
                hoppers.get(k).lost = true;
                hoppers.get(k).lostAt = lostAt[k];
            }
        qubits = Math.min(seatedHues.length, settledCount());
        System.arraycopy(seatedHues, 0, hues, 0, qubits);
        for (int i = 0; i < MAX_HOPPERS; i++) partner[i] = -1;
        for (int i = 0; i < qubits && i < partners.length; i++)
            if (partners[i] >= 0 && partners[i] < qubits) partner[i] = partners[i];
    }

    /**
     * Seats a qubit in the next empty pit, if a finished hopper has one waiting.
     * Returns whether it found a place.
     */
    boolean seat() {
        // pits of hoppers cut away are skipped, keeping qubit i in hopper i
        while (qubits < settledCount() && hoppers.get(qubits).lost) {
            hues[qubits] = VOID;
            partner[qubits] = -1;
            qubits++;
        }
        if (qubits >= settledCount()) return false;
        hues[qubits] = Math.min(1f, qubits / (float) (MAX_HOPPERS - 1));
        partner[qubits] = -1;
        qubits++;
        settledVersion++;
        return true;
    }

    /**
     * Fuses the newest qubits, up to count of them, into one white qubit. Fewer qubits means
     * the spectrum clock steps back. Returns how many were fused, zero if there was nothing to fuse.
     */
    int fuse(int count) {
        // the newest real qubits, walking back over the places of those cut away
        int taken = 0, i = qubits - 1;
        while (i >= 0 && taken < count) {
            if (realQubit(i)) taken++;
            if (taken < count) i--;
        }
        if (taken < 2) return 0;
        if (i < 0) {
            i = 0;
            while (i < qubits && !realQubit(i)) i++;
        }
        for (int k = i; k < qubits; k++) untangle(k);
        qubits = i + 1;
        hues[i] = WHITE;
        settledVersion++;
        return taken;
    }

    /** Sum of size squared times growth: how much crystal there is. */
    float mass() {
        float m = 0;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            if (h.lost) continue;
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
            if (p.lost) continue;
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
            if (g <= 0 || h.lost) continue;
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
            if (h.lost) continue;
            float w = h.size * h.size * ease(h.grown) + 1e-4f;
            sx += h.cx() * w; sy += h.cy() * w; sz += h.cz() * w; sw += w;
        }
        out[0] = sx / sw; out[1] = sy / sw; out[2] = sz / sw;
    }

    static float ease(float g) {
        float a = 1f - (g < 0 ? 0 : g > 1 ? 1 : g);
        return 1f - a * a * a;
    }

    /** Writes every finished hopper that rests above the surface; returns the vertex count. */
    int buildSettled(float[] out) {
        int v = 0;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            if (h != active && h.sink <= 0f) v = emitHopper(h, i, out, v);
        }
        return v;
    }

    /**
     * The lines of light between entangled qubits. They are drawn over everything, through
     * the crystal, because what links them is not inside the metal.
     */
    int buildThreads(float[] out, float time, float[] amp) {
        int v = 0;
        for (int i = 0; i < qubits; i++) {
            if (!stringShown(i)) continue;
            v = string(out, v, hoppers.get(i), hoppers.get(partner[i]), amp[i], time, i);
        }
        return v;
    }

    /** Whether qubit i owns a visible string: it has a partner after it and neither is under. */
    boolean stringShown(int i) {
        int j = i < qubits ? partner[i] : -1;
        return j > i && hoppers.get(i).sink <= 0f && hoppers.get(j).sink <= 0f
                && !hoppers.get(i).lost && !hoppers.get(j).lost;
    }

    /** Both ends of the string owned by qubit i, in cluster space. */
    void stringEnds(int i, float[] a, float[] b) {
        qubitAt(hoppers.get(i), a);
        qubitAt(hoppers.get(partner[i]), b);
    }

    /** How fast a string of this length is drawn to swing, per second: short strings are quicker. */
    static float swing(float len) {
        return Math.max(2.5f, Math.min(9f, 7f / Math.max(0.1f, len)));
    }

    static final int STRING_SEGMENTS = 18;

    /**
     * A string between two entangled qubits, vibrating as a standing wave: fixed at the qubits,
     * widest in the middle, with a little of the second harmonic and a slight sideways sway.
     */
    private int string(float[] out, int v, Hopper a, Hopper b, float amp, float time, int seed) {
        float[] pa = new float[3], pb = new float[3];
        qubitAt(a, pa);
        qubitAt(b, pb);
        float dx = pb[0] - pa[0], dy = pb[1] - pa[1], dz = pb[2] - pa[2];
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3f) return v;
        dx /= len; dy /= len; dz /= len;
        float ox = Math.abs(dy) < 0.9f ? 0f : 1f, oy = Math.abs(dy) < 0.9f ? 1f : 0f;
        float ux = -dz * oy, uy = dz * ox, uz = dx * oy - dy * ox;
        float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        float wx = dy * uz - dz * uy, wy = dz * ux - dx * uz, wz = dx * uy - dy * ux;
        float w = (float) (2.0 * Math.PI * swing(len)), phase = seed * 1.7f;
        float c1 = (float) Math.sin(w * time + phase), c2 = (float) Math.sin(2f * w * time + phase * 1.3f);
        float c3 = (float) Math.cos(w * 1.01f * time + phase);
        float[] p0 = new float[3], p1 = new float[3];
        for (int k = 0; k <= STRING_SEGMENTS; k++) {
            float t = k / (float) STRING_SEGMENTS;
            float s1 = (float) Math.sin(Math.PI * t), s2 = (float) Math.sin(2.0 * Math.PI * t);
            float du = amp * (s1 * c1 + 0.35f * s2 * c2) * len, dw = amp * 0.4f * s1 * c3 * len;
            p1[0] = pa[0] + dx * len * t + ux * du + wx * dw;
            p1[1] = pa[1] + dy * len * t + uy * du + wy * dw;
            p1[2] = pa[2] + dz * len * t + uz * du + wz * dw;
            if (k > 0) v = segment(out, v, p0, p1, ux, uy, uz);
            p0[0] = p1[0]; p0[1] = p1[1]; p0[2] = p1[2];
        }
        return v;
    }

    /** One straight piece of a string, a thin box from p0 to p1. */
    private int segment(float[] out, int v, float[] p0, float[] p1, float ux, float uy, float uz) {
        float ex = p1[0] - p0[0], ey = p1[1] - p0[1], ez = p1[2] - p0[2];
        float el = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (el < 1e-5f) return v;
        ex /= el; ey /= el; ez /= el;
        float wx = ey * uz - ez * uy, wy = ez * ux - ex * uz, wz = ex * uy - ey * ux;
        float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz) + 1e-6f;
        wx /= wl; wy /= wl; wz /= wl;
        float vx = wy * ez - wz * ey, vy = wz * ex - wx * ez, vz = wx * ey - wy * ex;
        float[] f = {ex, ey, ez, vx, vy, vz, wx, wy, wz,
                (p0[0] + p1[0]) * 0.5f, (p0[1] + p1[1]) * 0.5f, (p0[2] + p1[2]) * 0.5f};
        return box(out, v, f, 0, 0, 0, el * 0.5f + 0.003f, 0.008f, 0.008f, 0f, THREAD);
    }

    /** Where a settled hopper's qubit sits, written into p. */
    static void qubitAt(Hopper h, float[] p) {
        float s = h.size, base = s * 0.18f, t = s / h.rings, step = t * 0.75f;
        float y = base + step * (1f + h.jitter[h.rings - 1]) + t * 0.55f;
        for (int k = 0; k < 3; k++) p[k] = h.f[9 + k] + h.f[3 + k] * y;
    }

    /** Writes what moves every frame: the growing hopper and those going under or coming up. */
    int buildMoving(float[] out) {
        int v = 0;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            if (h == active || h.sink > 0f) v = emitHopper(h, i, out, v);
        }
        return v;
    }

    /** Most hoppers allowed under at once, so the cluster never empties. */
    static final int MAX_UNDER = 6;

    /** Sends a finished hopper under the surface. Returns false if it cannot go. */
    boolean submerge(int i, float spin) {
        Hopper h = hoppers.get(i);
        if (h == active || h.under || h.sink > 0f || h.lost) return false;
        int under = 0;
        for (int k = 0; k < hoppers.size(); k++) if (hoppers.get(k).under) under++;
        if (under >= MAX_UNDER) return false;
        h.under = true;
        h.underSpin = spin;
        // an entangled partner goes under in the same instant, wherever it is
        if (i < qubits && partner[i] >= 0) {
            Hopper o = hoppers.get(partner[i]);
            if (o != active && !o.under) {
                o.under = true;
                o.underSpin = spin;
            }
        }
        settledVersion++;
        return true;
    }

    /** Whether hopper i holds a qubit entangled with another. */
    boolean entangled(int i) {
        return i < qubits && partner[i] >= 0;
    }

    /**
     * Moves hoppers under and back. Returns the index of a hopper that has just started to
     * come back up after half a turn, or -1.
     */
    int updateSinking(float dt, float spin) {
        int rose = -1;
        for (int i = 0; i < hoppers.size(); i++) {
            Hopper h = hoppers.get(i);
            if (h.under && spin - h.underSpin >= (float) Math.PI) {
                h.under = false;
                rose = i;
            }
            if (h.under) {
                h.sink = Math.min(1f, h.sink + dt / 1.2f);
            } else if (h.sink > 0f) {
                h.sink = Math.max(0f, h.sink - dt / 1.8f);
                if (h.sink == 0f) settledVersion++;
            }
        }
        return rose;
    }

    /** Writes every hopper, growing or not; returns the vertex count. */
    int buildAll(float[] out) {
        int v = 0;
        for (int i = 0; i < hoppers.size(); i++) v = emitHopper(hoppers.get(i), i, out, v);
        return v;
    }

    /** Writes only the hopper still growing; returns the vertex count. */
    int buildActive(float[] out) {
        return active == null ? 0 : emitHopper(active, hoppers.size() - 1, out, 0);
    }

    private int emitHopper(Hopper h, int index, float[] out, int v) {
        float g = ease(h.grown);
        if (g <= 0.001f || h.sink >= 0.999f || h.lost) return v;
        int first = v;
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
        if (index < qubits && h != active && h.sink < 0.25f) {
            // the qubit rests on the lowest ring, in the pit, peeking over the next wall
            float floor = base + step * (1f + h.jitter[h.rings - 1]);
            float q = t * 0.55f;
            v = box(out, v, h.f, 0, floor + q, 0, q, q, q, hues[index], QUBIT);
        }
        if (h.sink > 0f) {
            // going under: down through the surface, darkening to the black of the liquid
            float drop = h.sink * h.size * 2.4f, dim = (1f - h.sink) * (1f - h.sink);
            for (int k = first; k < v; k++) {
                int o = k * FLOATS;
                out[o + 1] -= drop;
                if (out[o + 7] < 2f) out[o + 7] *= dim;
            }
        }
        return v;
    }

    /** Shade value that tells the shader a face belongs to a qubit; its film slot then holds the hue. */
    static final float QUBIT = 3f;
    /** Shade value for the line between entangled qubits. */
    static final float THREAD = 4f;

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
