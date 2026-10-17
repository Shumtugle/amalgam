package com.shumtugle.amalgam;

/**
 * Everything that happens, without anything that draws or sounds.
 * The renderer reads it every frame; the sound and the vibration read its events.
 */
final class World {
    /** Seconds between two breaths of the crystal. */
    static final float BEAT = 2.6f;
    /** How close to a breath a touch must land to resonate, in seconds either side. */
    static final float WINDOW = 0.2f;
    /** Energy of a touch that misses the breath, and of one that lands on it. */
    static final float TOUCH = 0.25f, RESONANT = 0.8f;
    /** Energy that trickles in by itself, per second. */
    static final float TRICKLE = 0.03f;
    /** Most energy the crystal can turn into growth per second; the rest waits in the pool. */
    static final float GROWTH_RATE = 1.1f;

    static final int EV_NONE = 0, EV_TOUCH = 1, EV_RESONANT = 2, EV_BREATH = 3, EV_SETTLED = 4;

    final Liquid liquid;
    final float aspect;
    Crystal crystal;
    /** Where the crystal was planted, in screen [0, 1], v from the bottom. */
    float seedU = 0.5f, seedV = 0.5f;
    float time;
    /** Energy waiting to become crystal. */
    float pool;
    /** Breath glow in [0, 1], peaking on the beat. */
    float pulse;
    /** Short flash after a resonant touch. */
    float flash;
    /** Slow turn of the cluster, radians. */
    float spin;

    private float beatStart;
    private int lastBeat = -1;
    private final long seed;

    /** Smoothed camera target and cluster radius, so growth never jerks the view. */
    private final float[] mid = new float[3];
    private float across = 1.2f, upward = 1.2f;
    private final float[] ext = new float[2];

    /** Events since the last read, oldest first. */
    private final int[] events = new int[16];
    private int eventCount;

    World(int cols, float aspect, long seed) {
        this.aspect = aspect;
        this.seed = seed;
        liquid = new Liquid(cols, Math.max(8, Math.round(cols * aspect)));
    }

    boolean planted() {
        return crystal != null;
    }

    /** A finger came down at (u, v). */
    void touch(float u, float v) {
        liquid.press(u, v, 2.6f, 1.3f);
        if (crystal == null) {
            crystal = new Crystal(seed);
            seedU = u;
            seedV = v;
            beatStart = time + BEAT * 0.5f;
            lastBeat = -1;
            pool += RESONANT;
            flash = 1f;
            event(EV_RESONANT);
            return;
        }
        if (Math.abs(fromBeat()) <= WINDOW) {
            pool += RESONANT;
            flash = 1f;
            float[] p = crystalScreen();
            liquid.press(p[0], p[1], 4.5f, 1.2f);
            event(EV_RESONANT);
        } else {
            pool += TOUCH;
            event(EV_TOUCH);
        }
    }

    /** A finger slid across the glass: ripples, no energy. */
    void drag(float u, float v) {
        liquid.press(u, v, 2.2f, 0.35f);
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
        pulse = (float) Math.exp(-(d * d) / (0.09f));
        int beat = (int) Math.floor((time - beatStart) / BEAT + 0.5f);
        if (beat != lastBeat && d >= 0f) {
            lastBeat = beat;
            float[] p = crystalScreen();
            liquid.press(p[0], p[1], 5f, 0.3f);
            event(EV_BREATH);
        }

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
    }

    private final float[] tmp = new float[3];

    /** How much crystal there is; the drone deepens with it. */
    float mass() {
        return crystal == null ? 0f : crystal.mass();
    }

    /**
     * Where the crystal sits on screen, in [0, 1]. It starts where it was planted and drifts
     * towards the middle as it grows, so a big cluster never hangs off the edge.
     */
    float[] crystalScreen() {
        float k = 1f / (1f + 0.35f * mass());
        return new float[] {0.5f + (seedU - 0.5f) * k, 0.52f + (seedV - 0.52f) * k};
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
        float fill = 0.45f + 0.4f * (1f - (float) Math.exp(-m * 0.25f));
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
        float[] p = crystalScreen();
        shift[0] = p[0] * 2f - 1f;
        shift[1] = p[1] * 2f - 1f;
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
