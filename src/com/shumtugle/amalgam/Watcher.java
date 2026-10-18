package com.shumtugle.amalgam;

import java.util.Random;

/**
 * Plays the game once handed the eye. It wanders first, touching aimlessly for a while; then it
 * follows the solution below, line by line, and nothing else:
 *
 * 1. Touch the water once to plant the crystal.
 * 2. Read the colour under you and speak its language, a word of three and then the same three
 *    again, until the world sings it back. Clear: look at drops on the breath. Lilac, violet,
 *    red: draw circles on still water until two strings stretch between qubits, then pluck them
 *    on the breath. Light blue, blue: send hoppers under on the breath. Lime: circle the glints
 *    that tremble between breaths. Off clear, while fewer than four qubits have come since the
 *    newest white one, look at drops between words: it feeds the crystal, and only clear hears it.
 * 3. Cut the world with one straight stroke from edge to edge, beside the crystal or through
 *    it; the side holding less of the crystal falls away. Then step 2 on the new colour.
 * 4. Once three surfaces have sung back, go on speaking the language under you until the
 *    crystal falls in.
 * 5. Turn the drop it leaves, round and round, until it has swallowed the world.
 *
 * It touches the world through the same calls a finger does.
 */
final class Watcher {
    private static final int WANDER = 0, LISTEN = 1, CUT = 2, CUTTING = 3;

    private final World w;
    private final Random rnd;
    private int phase = WANDER;
    private float left, wait;
    private float strokeLeft, sx, sy, sdx, sdy;
    private final float[] drops = new float[World.maxDrops() * 8];
    private int waited;
    private int lastBeat = -1;
    // a stroke in progress, one point a step: the cut or a circle
    private float[] pathU = new float[64], pathV = new float[64];
    private int pathN, pathAt;
    private int cutsDone;
    // plucking: the orders of strings tried, and where in the current one we are
    private int order, inOrder;
    private float ringWait;

    Watcher(World w, long seed) {
        this.w = w;
        rnd = new Random(seed);
        left = 25f + 35f * rnd.nextFloat();
    }

    void step(float dt) {
        if (!w.planted()) {
            if (w.dropLeft) {
                turnDrop();
                return;
            }
            // step 1
            w.touchAs(0.3f + 0.4f * rnd.nextFloat(), 0.3f + 0.4f * rnd.nextFloat());
            return;
        }
        if (w.cutAge >= 0f) return;
        if (pathAt < pathN) {
            // a stroke under way: one point a step
            if (pathAt == 0) w.touchAs(pathU[0], pathV[0]);
            else w.dragAs(pathU[pathAt], pathV[pathAt]);
            pathAt++;
            return;
        }
        switch (phase) {
            case WANDER:
                wander(dt);
                left -= dt;
                if (left <= 0f) phase = LISTEN;
                break;
            case CUTTING:
                // the cut has played out: read the new surface
                phase = LISTEN;
                break;
            default:
                boolean enough = Integer.bitCount(w.layersHeard) >= World.SURFACES_TO_END;
                if (heardHere() && !enough) {
                    // step 3: the world has sung back here; cut to the next colour
                    planCut();
                    phase = CUTTING;
                    break;
                }
                // steps 2 and 4: speak the language under you, and feed the crystal between words
                if (World.language(w.layer) != World.LOOKS && feed(dt)) break;
                switch (World.language(w.layer)) {
                    case World.STRINGS: strings(dt); break;
                    case World.SPIRALS: sinks(); break;
                    case World.GLINTS: glints(); break;
                    default: sides(); break;
                }
                break;
        }
    }

    // step 6: one long stroke round the drop, widening as the drop grows
    private float turnAt = -1f;

    private void turnDrop() {
        if (w.swallowAge >= 0f) return;
        float r = w.dropRadius() * 1.6f + 0.1f;
        boolean start = turnAt < 0f;
        if (start) turnAt = rnd.nextFloat() * 6.2832f;
        else turnAt += 0.032f;
        float u = w.dropX + (float) Math.cos(turnAt) * r, v = (w.dropY + (float) Math.sin(turnAt) * r) / w.aspect;
        if (start) w.touchAs(u, v);
        else w.dragAs(u, v);
    }

    private boolean heardHere() {
        return (w.layersHeard & (1 << w.layer)) != 0;
    }

    /** Aimless play: taps anywhere, a few drops, a hopper now and then, idle strokes. */
    private void wander(float dt) {
        if (strokeLeft > 0f) {
            strokeLeft -= dt;
            sx += sdx * dt;
            sy += sdy * dt;
            w.dragAs(clamp(sx), clamp(sy));
            return;
        }
        wait -= dt;
        if (wait > 0f) return;
        wait = 0.2f + 0.6f * rnd.nextFloat();
        float roll = rnd.nextFloat();
        w.drops(drops);
        int pd = w.pairDrops();
        if (roll < 0.25f && pd > 0) {
            int k = rnd.nextInt(pd);
            w.touchAs(drops[k * 8], drops[k * 8 + 1]);
        } else if (roll < 0.35f) {
            w.touchAs(w.crystalU() + (rnd.nextFloat() - 0.5f) * 0.15f, w.crystalV() + (rnd.nextFloat() - 0.5f) * 0.06f);
        } else if (roll < 0.5f) {
            sx = 0.2f + 0.6f * rnd.nextFloat();
            sy = 0.2f + 0.6f * rnd.nextFloat();
            float a = rnd.nextFloat() * 6.2832f;
            sdx = (float) Math.cos(a) * 0.5f;
            sdy = (float) Math.sin(a) * 0.25f;
            strokeLeft = 0.3f + 0.4f * rnd.nextFloat();
            w.touchAs(sx, sy);
        } else {
            w.touchAs(rnd.nextFloat(), rnd.nextFloat());
        }
    }

    /**
     * The side the word asks for next: the one heard three symbols ago, so the last three come
     * round again; -1 when anything will do, at the start of a word or when it has gone stale.
     */
    private int wantSide() {
        Listener l = w.listener;
        int run = l.length();
        if (run < 3) return -1;
        int want = l.at(run - 3) >> 1;
        boolean same = l.at(run - 1) >> 1 == l.at(run - 2) >> 1 && l.at(run - 2) >> 1 == l.at(run - 3) >> 1;
        return same || run > 9 ? -1 : want;
    }

    private float feedWait;

    /**
     * Between breaths, a look at a drop that is not part of any word, while too little colour
     * is left in the crystal for the next word to fold.
     */
    private boolean feed(float dt) {
        feedWait -= dt;
        if (feedWait > 0f || Math.abs(w.fromBeat()) < 0.7f) return false;
        // a word folds the newest qubits; with too few since the last white, it folds that too
        Crystal c = w.crystal;
        int fresh = 0;
        for (int i = c.qubits - 1; i >= 0; i--) {
            if (!c.realQubit(i)) continue;
            if (c.hues[i] > 1.5f) break;
            fresh++;
        }
        if (fresh >= 4) return false;
        int pd = w.pairDrops();
        if (pd == 0) return false;
        w.drops(drops);
        int k = rnd.nextInt(pd);
        feedWait = 1.2f + rnd.nextFloat();
        w.touchAs(drops[k * 8], drops[k * 8 + 1]);
        return true;
    }

    /** Clear: on the breath, look at a drop on the side the word asks for. */
    private void sides() {
        int beat = breath();
        if (beat < 0) return;
        int pd = w.pairDrops();
        if (pd == 0) return;
        w.drops(drops);
        int want = wantSide();
        int pick = -1;
        for (int k = 0; k < pd; k++) {
            if (want < 0 || side(drops[k * 8], drops[k * 8 + 1]) == want) { pick = k; break; }
        }
        if (pick < 0) {
            if (++waited < 3) { lastBeat = beat; return; }
            pick = 0;
        }
        waited = 0;
        lastBeat = beat;
        w.touchAs(drops[pick * 8], drops[pick * 8 + 1]);
    }

    /** Light blue and blue: on the breath, send under a hopper on the side the word asks for. */
    private void sinks() {
        int beat = breath();
        if (beat < 0) return;
        Crystal c = w.crystal;
        int under = 0;
        for (int i = 0; i < c.hoppers.size(); i++) if (c.hoppers.get(i).under) under++;
        if (under >= Crystal.MAX_UNDER) { lastBeat = beat; return; }
        int want = wantSide();
        int pick = -1, spare = -1;
        float[] at = null, spareAt = null;
        int n = c.hoppers.size(), from = rnd.nextInt(n);
        for (int k = 0; k < n; k++) {
            int i = (from + k) % n;
            Crystal.Hopper h = c.hoppers.get(i);
            if (h == c.active || h.under || h.sink > 0f || h.lost || c.entangled(i)) continue;
            float[] p = w.project(h.cx(), h.cy(), h.cz());
            if (p == null || dropNear(p[0], p[1])) continue;
            int sd = w.sideOf(i);
            if (want < 0 || sd == want) { pick = i; at = p; break; }
            if (spare < 0) { spare = i; spareAt = p; }
        }
        if (pick < 0) {
            if (++waited < 3 || spare < 0) { lastBeat = beat; return; }
            at = spareAt;
        }
        waited = 0;
        lastBeat = beat;
        w.touchAs(at[0], at[1]);
    }

    // glints let go on purpose, by the moment each lit up, so they are not taken after all
    private final float[] passed = new float[World.MAX_GLINTS];

    /** Lime: a quick circle round a fresh glint on the side the word asks for. */
    private void glints() {
        int want = wantSide();
        int pick = -1, spare = -1;
        int from = rnd.nextInt(World.MAX_GLINTS);
        for (int k = 0; k < World.MAX_GLINTS; k++) {
            int g = (from + k) % World.MAX_GLINTS;
            if (!w.gon[g] || w.gage[g] > 1.2f) continue;
            if (Math.abs(w.time - w.gage[g] - passed[g]) < 0.01f) continue;
            int sd = World.quarter(w.gx[g] - w.crystalU(), w.gy[g] - w.crystalV() * w.aspect);
            if (want < 0 || sd == want) { pick = g; break; }
            if (spare < 0) spare = g;
        }
        if (pick < 0) {
            if (spare < 0) return;
            if (++waited < 3) {
                // let this one go and wait for another
                passed[spare] = w.time - w.gage[spare];
                return;
            }
            pick = spare;
        }
        waited = 0;
        float r = 0.085f, cx = w.gx[pick], cy = w.gy[pick];
        float a0 = rnd.nextFloat() * 6.2832f;
        pathN = 26;
        for (int i = 0; i < pathN; i++) {
            float a = a0 + i / 22f * 6.2832f;
            pathU[i] = cx + (float) Math.cos(a) * r;
            pathV[i] = (cy + (float) Math.sin(a) * r) / w.aspect;
        }
        pathAt = 0;
    }

    /**
     * Step 3: a straight stroke from edge to edge. Every other one goes beside the crystal, near
     * the far edge; the rest go through it, a little off its middle and at a slant.
     */
    private void planCut() {
        float a = w.aspect;
        // the line through (px, py) in space units, along (dx, dy)
        float px, py, dx, dy;
        if (cutsDone % 2 == 0) {
            px = 0.5f;
            py = w.crystalV() < 0.5f ? a - 0.11f : 0.11f;
            dx = 1f;
            dy = 0f;
        } else {
            px = w.crystalU() + (rnd.nextFloat() - 0.5f) * 0.12f;
            py = w.crystalV() * a + (rnd.nextFloat() - 0.5f) * 0.12f;
            double t = (rnd.nextBoolean() ? 0.0 : Math.PI * 0.5) + (rnd.nextFloat() - 0.5f) * 0.9f;
            dx = (float) Math.cos(t);
            dy = (float) Math.sin(t);
        }
        // where the line enters and leaves the glass, just inside its edges
        float lo = -1e9f, hi = 1e9f;
        if (Math.abs(dx) > 1e-4f) {
            float t0 = (0.02f - px) / dx, t1 = (0.98f - px) / dx;
            lo = Math.max(lo, Math.min(t0, t1));
            hi = Math.min(hi, Math.max(t0, t1));
        }
        if (Math.abs(dy) > 1e-4f) {
            float t0 = (0.02f - py) / dy, t1 = (a - 0.02f - py) / dy;
            lo = Math.max(lo, Math.min(t0, t1));
            hi = Math.min(hi, Math.max(t0, t1));
        }
        pathN = 40;
        for (int i = 0; i < pathN; i++) {
            float s = lo + (hi - lo) * i / (pathN - 1);
            pathU[i] = px + dx * s;
            pathV[i] = (py + dy * s) / a;
        }
        pathAt = 0;
        cutsDone++;
    }

    /** Step 4 on surfaces that hear strings: make two strings if needed, then pluck an order twice. */
    private void strings(float dt) {
        int[] shown = shownStrings();
        if (shown.length < 2) {
            if (w.ringOn) return;
            ringWait -= dt;
            if (ringWait > 0f) return;
            planCircle();
            ringWait = 1.5f;
            return;
        }
        int beat = breath();
        if (beat < 0) return;
        int[] seq = orderOf(shown, order);
        int target = seq[inOrder % 3];
        float[] p = middleOf(target);
        if (p == null || dropNear(p[0], p[1])) { lastBeat = beat; return; }
        int before = w.layersHeard, run = w.listener.length();
        w.touchAs(p[0], p[1]);
        lastBeat = beat;
        inOrder++;
        // heard: try the next order next time, so the world never grows used to it
        if (w.listener.length() < run || w.layersHeard != before) { order++; inOrder = 0; }
        if (inOrder >= 12) { order++; inOrder = 0; }
    }

    /** A circle on still water, away from the crystal. */
    private void planCircle() {
        float cv = w.crystalV() < 0.5f ? 0.76f : 0.24f;
        float r = 0.14f;
        pathN = 44;
        for (int i = 0; i < pathN; i++) {
            float a = i / 40f * 6.2832f;
            pathU[i] = 0.5f + (float) Math.cos(a) * r;
            pathV[i] = cv + (float) Math.sin(a) * r / w.aspect;
        }
        pathAt = 0;
    }

    /** The strings that show now, by the qubit that owns each. */
    private int[] shownStrings() {
        Crystal c = w.crystal;
        int n = 0;
        int[] tmp = new int[Crystal.MAX_HOPPERS];
        for (int i = 0; i < c.qubits; i++) if (c.stringShown(i)) tmp[n++] = i;
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    /** Order k of three strings out of those shown: always at least two different ones. */
    private static int[] orderOf(int[] s, int k) {
        int n = s.length;
        int a = k % n, b = (k / n + 1 + a) % n;
        if (b == a) b = (a + 1) % n;
        int c = (k % 3 == 0) ? a : (k % 3 == 1 ? b : (b + 1) % n);
        return new int[] {s[a], s[b], s[c]};
    }

    /** The middle of a string on screen. */
    private float[] middleOf(int owner) {
        float[] a = new float[3], b = new float[3];
        w.crystal.stringEnds(owner, a, b);
        float[] p = w.project((a[0] + b[0]) * 0.5f, (a[1] + b[1]) * 0.5f, (a[2] + b[2]) * 0.5f);
        return p == null ? null : new float[] {p[0], p[1]};
    }

    private boolean dropNear(float u, float v) {
        int n = w.drops(drops);
        for (int k = 0; k < n; k++) {
            float dx = drops[k * 8] - u, dy = (drops[k * 8 + 1] - v) * w.aspect;
            if (dx * dx + dy * dy < 0.12f * 0.12f) return true;
        }
        return false;
    }

    /** The current breath, if we are on it and have not acted on it yet; -1 otherwise. */
    private int breath() {
        if (Math.abs(w.fromBeat()) > 0.15f) return -1;
        int beat = Math.round(w.time / World.BEAT);
        return beat == lastBeat ? -1 : beat;
    }

    /** Side of the crystal a screen point is on, as the world hears it. */
    private int side(float u, float v) {
        float x = u - w.crystalU(), y = (v - w.crystalV()) * w.aspect;
        return Math.abs(x) > Math.abs(y) ? (x > 0 ? 1 : 3) : (y > 0 ? 0 : 2);
    }

    private static float clamp(float v) {
        return Math.max(0.12f, Math.min(0.88f, v));
    }
}
