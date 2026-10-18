package com.shumtugle.amalgam;

import java.util.Random;

/**
 * Plays the game on its own once handed the eye. First it wanders deeper into disorder with
 * aimless touches for a while; then it settles down and solves: it looks at drops only on the
 * breath, repeats what it has just played until the world hears a pattern, and keeps folding
 * qubits into white until the crystal falls in.
 *
 * It touches the world through the same calls a finger does, nothing more.
 */
final class Watcher {
    private static final int WANDER = 0, SOLVE = 1;

    private final World w;
    private final Random rnd;
    private int phase = WANDER;
    private float left, wait;
    private float strokeLeft, sx, sy, sdx, sdy;
    private final float[] drops = new float[World.maxDrops() * 8];
    /** Breaths spent waiting for one wanted side; past a few, the run is given up. */
    private int waited;
    private int lastBeat = -1;

    Watcher(World w, long seed) {
        this.w = w;
        rnd = new Random(seed);
        left = 25f + 35f * rnd.nextFloat();
    }

    void step(float dt) {
        if (!w.planted()) {
            w.touchAs(0.3f + 0.4f * rnd.nextFloat(), 0.3f + 0.4f * rnd.nextFloat());
            return;
        }
        if (phase == WANDER) {
            wander(dt);
            left -= dt;
            if (left <= 0f) phase = SOLVE;
        } else {
            solve();
        }
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
        int n = w.drops(drops), pd = w.pairDrops();
        if (roll < 0.25f && pd > 0) {
            int k = rnd.nextInt(pd);
            w.touchAs(drops[k * 8], drops[k * 8 + 1]);
        } else if (roll < 0.35f) {
            w.touchAs(w.crystalU() + (rnd.nextFloat() - 0.5f) * 0.15f, w.crystalV() + (rnd.nextFloat() - 0.5f) * 0.06f);
        } else if (roll < 0.5f) {
            sx = rnd.nextFloat();
            sy = rnd.nextFloat();
            float a = rnd.nextFloat() * 6.2832f;
            sdx = (float) Math.cos(a) * 0.9f;
            sdy = (float) Math.sin(a) * 0.4f;
            strokeLeft = 0.3f + 0.5f * rnd.nextFloat();
            w.touchAs(sx, sy);
        } else {
            w.touchAs(rnd.nextFloat(), rnd.nextFloat());
        }
    }

    /**
     * Deliberate play: only on the breath, and always the side the run asks for, so the last
     * symbols repeat and the world hears them.
     */
    private void solve() {
        float d = w.fromBeat();
        if (Math.abs(d) > 0.2f) return;
        int beat = Math.round((w.time) / World.BEAT);
        if (beat == lastBeat) return;
        int pd = w.pairDrops();
        if (pd == 0) return;
        w.drops(drops);
        Listener l = w.listener;
        int run = l.length();
        int want = -1;
        if (run >= 3) {
            want = l.at(run - 3) >> 1;
            // a run of one side alone is no pattern, and a run that never closes is familiar
            boolean same = l.at(run - 1) >> 1 == l.at(run - 2) >> 1 && l.at(run - 2) >> 1 == l.at(run - 3) >> 1;
            if (same || run > 9) want = -1;
        }
        int pick = -1;
        for (int k = 0; k < pd; k++) {
            int side = side(drops[k * 8], drops[k * 8 + 1]);
            if (want < 0 || side == want) { pick = k; break; }
        }
        if (pick < 0) {
            // the wanted side did not come this breath; after a few, take what there is
            if (++waited < 3) { lastBeat = beat; return; }
            pick = 0;
        }
        waited = 0;
        lastBeat = beat;
        w.touchAs(drops[pick * 8], drops[pick * 8 + 1]);
    }

    /** Side of the crystal a screen point is on, as the world hears it. */
    private int side(float u, float v) {
        float x = u - w.crystalU(), y = (v - w.crystalV()) * w.aspect;
        return Math.abs(x) > Math.abs(y) ? (x > 0 ? 1 : 3) : (y > 0 ? 0 : 2);
    }

    private static float clamp(float v) {
        return Math.max(0.02f, Math.min(0.98f, v));
    }
}
