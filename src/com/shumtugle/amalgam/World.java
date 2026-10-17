package com.shumtugle.amalgam;

import java.util.Random;

/**
 * Everything that happens, without anything that draws or sounds.
 * The renderer reads it every frame; the sound and the vibration read its events.
 *
 * The liquid is a vacuum. Where its waves pile up, pairs of drops rise out of it and, unless
 * someone looks, sink back as if they had never been. A touch on a drop is an observation:
 * the pair becomes real, one drop falls past the crystal's horizon and feeds it, the other
 * escapes and is heard. That escaping sound is the currency, resonance.
 *
 * Positions inside are in space units: x across the screen from 0 to 1, y up from 0 to
 * the aspect ratio, so distances are the same in both directions. Touches arrive and
 * drawings leave in screen units, u and v in [0, 1].
 */
final class World {
    /** Seconds between two breaths of the crystal. */
    static final float BEAT = 2.6f;
    /** How close to a breath an observation must land to count twice, seconds either side. */
    static final float WINDOW = 0.25f;
    /** Energy a fallen drop brings the crystal, and resonance an escaping one brings. */
    static final float FALL_ENERGY = 0.6f, ESCAPE_RESONANCE = 1f;
    /** Energy that trickles in by itself, per second, and the most a long absence can bring. */
    static final float TRICKLE = 0.03f, AWAY_CAP = 4f;
    /** Most energy the crystal can turn into growth per second; the rest waits in the pool. */
    static final float GROWTH_RATE = 1.1f;

    static final int EV_PLANT = 1, EV_OBSERVE = 2, EV_OBSERVE_ON_BEAT = 3, EV_MISS = 4,
            EV_ABSORB = 5, EV_BREATH = 6, EV_SETTLED = 7;

    // virtual pairs
    static final int MAX_PAIRS = 5;
    /** Lifetime of an unobserved pair, seconds. */
    private static final float PAIR_LIFE = 1.9f;
    /** Widest gap between the two drops of a pair, and the drop radius, in space units. */
    private static final float PAIR_GAP = 0.06f, DROP = 0.03f;
    /** A touch this close to a drop observes it. Generous: a fingertip is wide. */
    private static final float HIT = 0.08f;
    /** A touch on empty liquid unsettles pairs this close. */
    private static final float DECOHERE = 0.3f;
    /** Height a wave must pile up to before a pair can rise from it unprompted. */
    private static final float CREST = 0.12f;
    /** Pairs that rise between breaths, per second, at most. */
    private static final float SPONTANEOUS = 0.35f;

    // real drops in flight
    static final int MAX_FLYERS = 12;
    private static final int FALLING = 0, ESCAPING = 1;

    final Liquid liquid;
    final float aspect;
    final long seed;
    Crystal crystal;
    /** Where the crystal was planted, in screen units. */
    float seedU = 0.5f, seedV = 0.5f;
    float time;
    /** Energy waiting to become crystal. */
    float pool;
    /** The currency. Heard in the string, never shown. */
    float resonance;
    /** Breath glow in [0, 1], peaking on the beat. */
    float pulse;
    /** Short flash after something falls in or an observation lands on the breath. */
    float flash;
    /** Slow turn of the cluster, radians. */
    float spin;

    private float beatStart;
    private int lastBeat = -1;
    private final Random rnd;
    private float spontaneous;

    private final float[] px = new float[MAX_PAIRS], py = new float[MAX_PAIRS];
    private final float[] pax = new float[MAX_PAIRS], pay = new float[MAX_PAIRS];
    private final float[] page = new float[MAX_PAIRS], plife = new float[MAX_PAIRS];
    private final float[] pfilm = new float[MAX_PAIRS];
    private final boolean[] pon = new boolean[MAX_PAIRS];

    private final float[] fx = new float[MAX_FLYERS], fy = new float[MAX_FLYERS];
    private final float[] fvx = new float[MAX_FLYERS], fvy = new float[MAX_FLYERS];
    private final float[] fval = new float[MAX_FLYERS], fage = new float[MAX_FLYERS];
    private final float[] ffilm = new float[MAX_FLYERS], ftrail = new float[MAX_FLYERS];
    private final int[] fkind = new int[MAX_FLYERS];
    private final boolean[] fon = new boolean[MAX_FLYERS];

    /** Smoothed camera target and cluster extent, so growth never jerks the view. */
    private final float[] mid = new float[3];
    private float across = 1.2f, upward = 1.2f;
    private final float[] ext = new float[2];
    private final float[] tmp = new float[3];

    /** The crystal's outline on screen, space units: left, bottom, right, top. */
    private final float[] box = new float[4];
    private float boxAge = 1f;
    private final float[] cMvp = new float[16], cModel = new float[16], cEye = new float[3], cShift = new float[2];

    private final int[] events = new int[32];
    private int eventCount;

    World(int cols, float aspect, long seed) {
        this.aspect = aspect;
        this.seed = seed;
        rnd = new Random(seed ^ 0x5DEECE66DL);
        liquid = new Liquid(cols, Math.max(8, Math.round(cols * aspect)));
    }

    boolean planted() {
        return crystal != null;
    }

    private void plant(float u, float v) {
        crystal = new Crystal(seed);
        seedU = u;
        seedV = v;
        beatStart = time + BEAT * 0.5f;
        lastBeat = -1;
        boxAge = 1f;
    }

    /**
     * Brings back a saved game. Away is how long the game was closed, in seconds;
     * the crystal grew a little by itself meanwhile.
     */
    void restore(float u, float v, int settled, float grown, float savedPool, float savedResonance, float away) {
        plant(u, v);
        crystal.restore(settled, grown);
        pool = savedPool + Math.min(AWAY_CAP, Math.max(0f, away) * TRICKLE);
        resonance = savedResonance;
        crystal.middle(mid);
        crystal.reach(mid[0], mid[1], mid[2], ext);
        across = ext[0];
        upward = ext[1];
        measureBox();
    }

    /** A finger came down at (u, v). */
    void touch(float u, float v) {
        liquid.press(u, v, 2.6f, 1.1f);
        if (crystal == null) {
            plant(u, v);
            pool += FALL_ENERGY;
            flash = 1f;
            event(EV_PLANT);
            return;
        }
        float x = u, y = v * aspect;
        int best = -1, side = 0;
        float bestD = HIT * HIT;
        for (int i = 0; i < MAX_PAIRS; i++) {
            if (!pon[i]) continue;
            for (int s = -1; s <= 1; s += 2) {
                float g = gap(i) * s;
                float dx = px[i] + pax[i] * g - x, dy = py[i] + pay[i] * g - y;
                float d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                    side = s;
                }
            }
        }
        if (best >= 0) observe(best, side);
        else miss(x, y);
    }

    /** A finger slid across the glass: ripples, nothing else. */
    void drag(float u, float v) {
        liquid.press(u, v, 2.2f, 0.35f);
    }

    /** Looking at a pair makes it real: one drop falls in, the other escapes. */
    private void observe(int i, int side) {
        pon[i] = false;
        boolean onBeat = Math.abs(fromBeat()) <= WINDOW;
        float k = onBeat ? 2f : 1f;
        float g = gap(i);
        float ax = px[i] + pax[i] * g, ay = py[i] + pay[i] * g;
        float bx = px[i] - pax[i] * g, by = py[i] - pay[i] * g;
        float cx = crystalX(), cy = crystalY();
        // the drop nearer the horizon is the one that falls
        boolean aFalls = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy) < (bx - cx) * (bx - cx) + (by - cy) * (by - cy);
        launch(aFalls ? ax : bx, aFalls ? ay : by, FALLING, FALL_ENERGY * k, pfilm[i]);
        launch(aFalls ? bx : ax, aFalls ? by : ay, ESCAPING, ESCAPE_RESONANCE * k, pfilm[i]);
        resonance += ESCAPE_RESONANCE * k;
        if (onBeat) flash = Math.max(flash, 0.8f);
        event(onBeat ? EV_OBSERVE_ON_BEAT : EV_OBSERVE);
    }

    /** Looking at empty liquid finds nothing and unsettles what was nearby. */
    private void miss(float x, float y) {
        for (int i = 0; i < MAX_PAIRS; i++) {
            if (!pon[i]) continue;
            float dx = px[i] - x, dy = py[i] - y;
            if (dx * dx + dy * dy < DECOHERE * DECOHERE)
                plife[i] = Math.min(plife[i], page[i] + 0.25f);
        }
        event(EV_MISS);
    }

    private void launch(float x, float y, int kind, float value, float film) {
        for (int j = 0; j < MAX_FLYERS; j++) {
            if (fon[j]) continue;
            float cx = crystalX(), cy = crystalY();
            float dx = x - cx, dy = y - cy;
            float d = (float) Math.sqrt(dx * dx + dy * dy) + 1e-4f;
            fon[j] = true;
            fkind[j] = kind;
            fx[j] = x;
            fy[j] = y;
            fval[j] = value;
            fage[j] = 0f;
            ffilm[j] = film;
            ftrail[j] = 0f;
            if (kind == FALLING) {
                // a sideways push, so the fall becomes a tightening spiral rather than a line
                float t = (rnd.nextBoolean() ? 1f : -1f) * 0.22f;
                fvx[j] = -dy / d * t;
                fvy[j] = dx / d * t;
            } else {
                fvx[j] = dx / d * 0.25f;
                fvy[j] = dy / d * 0.25f;
            }
            return;
        }
    }

    /** Signed seconds from the nearest breath; negative before it. */
    float fromBeat() {
        if (crystal == null) return BEAT;
        float t = time - beatStart;
        float n = (float) Math.floor(t / BEAT + 0.5f);
        return t - n * BEAT;
    }

    void update(float dt) {
        time += dt;
        flash = Math.max(0f, flash - dt * 1.8f);
        if (crystal == null) return;

        float d = fromBeat();
        pulse = (float) Math.exp(-(d * d) / 0.09f);
        int beat = (int) Math.floor((time - beatStart) / BEAT + 0.5f);
        if (beat != lastBeat && d >= 0f) {
            lastBeat = beat;
            liquid.press(crystalU(), crystalV(), 5f, 0.3f);
            event(EV_BREATH);
            int n = mass() > 3f ? 2 : 1;
            for (int i = 0; i < n; i++) birth(true);
        }

        spontaneous += dt * SPONTANEOUS;
        if (spontaneous >= 1f) {
            spontaneous -= 1f;
            if (rnd.nextFloat() < 0.8f) birth(false);
        }

        updatePairs(dt);
        updateFlyers(dt);

        pool += TRICKLE * dt;
        float use = Math.min(pool, GROWTH_RATE * dt);
        int before = crystal.settledVersion;
        float left = crystal.feed(use);
        pool -= use - left;
        if (crystal.settledVersion != before) event(EV_SETTLED);

        spin += dt * 0.11f;
        crystal.middle(tmp);
        crystal.reach(tmp[0], tmp[1], tmp[2], ext);
        float k = Math.min(1f, dt * 1.5f);
        for (int i = 0; i < 3; i++) mid[i] += (tmp[i] - mid[i]) * k;
        across += (ext[0] - across) * k;
        upward += (ext[1] - upward) * k;

        boxAge += dt;
        if (boxAge > 0.25f) {
            boxAge = 0f;
            measureBox();
        }
    }

    /** Projects every hopper's corners through the camera to find where the crystal shows. */
    private void measureBox() {
        camera(cMvp, cModel, cEye, cShift);
        float x0 = 1e9f, y0 = 1e9f, x1 = -1e9f, y1 = -1e9f;
        for (int i = 0; i < crystal.hoppers.size(); i++) {
            Crystal.Hopper h = crystal.hoppers.get(i);
            float g = Crystal.ease(h.grown);
            if (g <= 0f) continue;
            float r = h.size * g;
            for (int c = 0; c < 8; c++) {
                float wx = h.cx() + ((c & 1) == 0 ? -r : r);
                float wy = h.cy() + ((c & 2) == 0 ? -r : r);
                float wz = h.cz() + ((c & 4) == 0 ? -r : r);
                float cx = cMvp[0] * wx + cMvp[4] * wy + cMvp[8] * wz + cMvp[12];
                float cy = cMvp[1] * wx + cMvp[5] * wy + cMvp[9] * wz + cMvp[13];
                float cw = cMvp[3] * wx + cMvp[7] * wy + cMvp[11] * wz + cMvp[15];
                if (cw <= 1e-4f) continue;
                float u = (cx / cw + cShift[0]) * 0.5f + 0.5f;
                float v = (cy / cw + cShift[1]) * 0.5f + 0.5f;
                x0 = Math.min(x0, u); x1 = Math.max(x1, u);
                y0 = Math.min(y0, v * aspect); y1 = Math.max(y1, v * aspect);
            }
        }
        if (x1 < x0) return;
        box[0] = x0; box[1] = y0; box[2] = x1; box[3] = y1;
    }

    /**
     * A pair rises where the waves pile highest. On a breath one always rises: if the liquid
     * is calm, out of the ring the breath itself sends round the crystal.
     */
    private void birth(boolean breath) {
        int slot = -1;
        for (int i = 0; i < MAX_PAIRS; i++) if (!pon[i]) { slot = i; break; }
        if (slot < 0) return;
        float x = 0, y = 0;
        boolean found = false;
        for (int tries = 0; tries < 6 && !found; tries++) {
            float h = liquid.crest(rnd, 48, tmp);
            if (h < CREST && !breath) return;
            if (h >= CREST) {
                x = tmp[0];
                y = tmp[1] * aspect;
            } else {
                float a = rnd.nextFloat() * 6.2832f, extra = 0.1f + 0.15f * rnd.nextFloat();
                x = crystalX() + (float) Math.cos(a) * (halfW() + extra);
                y = crystalY() + (float) Math.sin(a) * (halfH() + extra);
            }
            found = roomFor(x, y);
        }
        if (!found) return;
        float a = rnd.nextFloat() * 6.2832f;
        pon[slot] = true;
        px[slot] = x;
        py[slot] = y;
        pax[slot] = (float) Math.cos(a);
        pay[slot] = (float) Math.sin(a);
        page[slot] = 0f;
        plife[slot] = PAIR_LIFE;
        pfilm[slot] = 120f + rnd.nextFloat() * 200f;
        liquid.press(x, y / aspect, 1.8f, -0.45f);
    }

    private boolean roomFor(float x, float y) {
        if (x < 0.08f || x > 0.92f || y < 0.08f || y > aspect - 0.08f) return false;
        if (x > box[0] - 0.06f && x < box[2] + 0.06f && y > box[1] - 0.06f && y < box[3] + 0.06f) return false;
        for (int i = 0; i < MAX_PAIRS; i++) {
            if (!pon[i]) continue;
            float dx = px[i] - x, dy = py[i] - y;
            if (dx * dx + dy * dy < 0.12f * 0.12f) return false;
        }
        return true;
    }

    private void updatePairs(float dt) {
        for (int i = 0; i < MAX_PAIRS; i++) {
            if (!pon[i]) continue;
            page[i] += dt;
            if (page[i] >= plife[i]) {
                // unobserved: the two meet and sink, leaving only a dimple
                pon[i] = false;
                liquid.press(px[i], py[i] / aspect, 1.6f, 0.3f);
            }
        }
    }

    private void updateFlyers(float dt) {
        float cx = crystalX(), cy = crystalY();
        float capture = Math.min(halfW(), halfH()) * 0.55f;
        for (int j = 0; j < MAX_FLYERS; j++) {
            if (!fon[j]) continue;
            fage[j] += dt;
            float dx = cx - fx[j], dy = cy - fy[j];
            float d2 = dx * dx + dy * dy, d = (float) Math.sqrt(d2) + 1e-4f;
            if (fkind[j] == FALLING) {
                float a = Math.min(7f, 0.08f / (d2 + 0.004f));
                fvx[j] += dx / d * a * dt;
                fvy[j] += dy / d * a * dt;
                float drag = 1f - Math.min(1f, 0.9f * dt);
                fvx[j] *= drag;
                fvy[j] *= drag;
                if (d < capture || fage[j] > 4f) {
                    fon[j] = false;
                    pool += fval[j];
                    flash = Math.max(flash, 0.6f);
                    liquid.press(crystalU(), crystalV(), 3.5f, 0.6f);
                    event(EV_ABSORB);
                    continue;
                }
            } else {
                float sp = (float) Math.sqrt(fvx[j] * fvx[j] + fvy[j] * fvy[j]) + 1e-4f;
                float boost = 1f + 1.6f * dt;
                fvx[j] *= boost;
                fvy[j] *= boost;
                ftrail[j] += sp * dt;
                if (ftrail[j] > 0.035f) {
                    ftrail[j] = 0f;
                    liquid.press(fx[j], fy[j] / aspect, 1.6f, 0.18f);
                }
                if (fx[j] < -0.1f || fx[j] > 1.1f || fy[j] < -0.1f || fy[j] > aspect + 0.1f) {
                    fon[j] = false;
                    continue;
                }
            }
            fx[j] += fvx[j] * dt;
            fy[j] += fvy[j] * dt;
        }
    }

    private float gap(int i) {
        return PAIR_GAP * (float) Math.sin(Math.PI * Math.min(1f, page[i] / plife[i]));
    }

    /** How big a drop of a pair is at its age: it swells up, and shrinks as the pair closes. */
    private float pairSize(int i) {
        float t = page[i], left = plife[i] - t;
        float in = Math.min(1f, t / 0.25f), out = Math.min(1f, left / 0.3f);
        return DROP * in * Math.max(0f, out);
    }

    /**
     * Everything drop-shaped for the renderer, eight floats each:
     * u, v, radius as a share of screen width, opacity, film thickness,
     * stretch direction x and y, stretch. Returns how many.
     */
    int drops(float[] out) {
        int n = 0;
        for (int i = 0; i < MAX_PAIRS; i++) {
            if (!pon[i]) continue;
            float r = pairSize(i), g = gap(i);
            for (int s = -1; s <= 1; s += 2) {
                int o = n * 8;
                out[o] = px[i] + pax[i] * g * s;
                out[o + 1] = (py[i] + pay[i] * g * s) / aspect;
                out[o + 2] = r;
                out[o + 3] = 1f;
                out[o + 4] = pfilm[i];
                out[o + 5] = 1f;
                out[o + 6] = 0f;
                out[o + 7] = 1f;
                n++;
            }
        }
        float cx = crystalX(), cy = crystalY();
        float capture = Math.min(halfW(), halfH()) * 0.55f;
        for (int j = 0; j < MAX_FLYERS; j++) {
            if (!fon[j]) continue;
            float sp = (float) Math.sqrt(fvx[j] * fvx[j] + fvy[j] * fvy[j]) + 1e-4f;
            float r = DROP, stretch = 1f + Math.min(2.5f, sp * 1.5f);
            if (fkind[j] == FALLING) {
                // squeezed thin as it nears the horizon
                float dx = cx - fx[j], dy = cy - fy[j];
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                r *= Math.max(0.15f, Math.min(1f, 0.3f + 0.7f * (d - capture) / (capture * 1.5f)));
            }
            int o = n * 8;
            out[o] = fx[j];
            out[o + 1] = fy[j] / aspect;
            out[o + 2] = r;
            out[o + 3] = fkind[j] == ESCAPING ? Math.max(0f, 1f - fage[j] * 0.6f) : 1f;
            out[o + 4] = ffilm[j];
            out[o + 5] = fvx[j] / sp;
            out[o + 6] = fvy[j] / sp;
            out[o + 7] = stretch;
            n++;
        }
        return n;
    }

    /** How many of the drops written by drops() belong to unobserved pairs; they come first. */
    int pairDrops() {
        int n = 0;
        for (int i = 0; i < MAX_PAIRS; i++) if (pon[i]) n += 2;
        return n;
    }

    static int maxDrops() {
        return MAX_PAIRS * 2 + MAX_FLYERS;
    }

    /** How much crystal there is: the string thickens with it and space bends round it. */
    float mass() {
        return crystal == null ? 0f : crystal.mass();
    }

    /** Share of the screen width the cluster covers; the camera keeps it there. */
    private float fill() {
        return 0.45f + 0.4f * (1f - (float) Math.exp(-mass() * 0.25f));
    }

    private float halfW() {
        return Math.max(0.05f, (box[2] - box[0]) * 0.5f);
    }

    private float halfH() {
        return Math.max(0.05f, (box[3] - box[1]) * 0.5f);
    }

    /** Strength of the bending of the liquid round the crystal. */
    float lens() {
        return Math.min(0.012f, 0.0011f * mass());
    }

    /**
     * Where the crystal sits on screen, in screen units. It starts where it was planted and
     * drifts towards the middle as it grows, so a big cluster never hangs off the edge.
     */
    float crystalU() {
        return 0.5f + (seedU - 0.5f) / (1f + 0.35f * mass());
    }

    float crystalV() {
        return 0.52f + (seedV - 0.52f) / (1f + 0.35f * mass());
    }

    /** Visual middle of the crystal on screen, where drops fall to. */
    private float crystalX() {
        return box[2] > box[0] ? (box[0] + box[2]) * 0.5f : crystalU();
    }

    private float crystalY() {
        return box[3] > box[1] ? (box[1] + box[3]) * 0.5f : crystalV() * aspect;
    }

    /**
     * Camera for the crystal. Fills mvp, model and eye (xyz), and shift: the crystal's
     * screen offset in clip space, applied after projection.
     */
    void camera(float[] mvp, float[] model, float[] eye, float[] shift) {
        float fovy = 32f;
        float tanY = (float) Math.tan(Math.toRadians(fovy) * 0.5);
        float tanX = tanY / aspect;
        float m = mass();
        float fill = fill();
        float el = (float) Math.toRadians(52);
        // Seen from above at an angle, height shows foreshortened by the cosine of the elevation.
        float tall = upward * (float) Math.cos(el) + across * (float) Math.sin(el);
        float dist = Math.max(across / (fill * tanX), tall / (0.6f * tanY));
        float radius = Math.max(across, upward);
        eye[0] = mid[0];
        eye[1] = mid[1] + dist * (float) Math.sin(el);
        eye[2] = mid[2] + dist * (float) Math.cos(el);
        float[] proj = new float[16], view = new float[16], pv = new float[16];
        Mat.perspective(proj, fovy, 1f / aspect, Math.max(0.05f, dist - radius * 3f), dist + radius * 3f);
        Mat.lookAt(view, eye[0], eye[1], eye[2], mid[0], mid[1], mid[2]);
        Mat.spinAround(model, spin, mid[0], mid[1], mid[2]);
        Mat.mul(pv, proj, view);
        Mat.mul(mvp, pv, model);
        shift[0] = crystalU() * 2f - 1f;
        shift[1] = crystalV() * 2f - 1f;
    }

    private void event(int e) {
        if (eventCount < events.length) events[eventCount++] = e;
    }

    /** Copies pending events into out and clears them; returns how many. */
    int takeEvents(int[] out) {
        int n = Math.min(eventCount, out.length);
        System.arraycopy(events, 0, out, 0, n);
        eventCount = 0;
        return n;
    }
}
