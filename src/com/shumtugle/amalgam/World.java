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
            EV_ABSORB = 5, EV_BREATH = 6, EV_SETTLED = 7, EV_QUBIT = 8, EV_PATTERN = 9,
            EV_SINK = 10, EV_SURFACE = 11, EV_VISTA = 12, EV_RING = 13, EV_BOUNCE = 14, EV_ENTANGLE = 15,
            EV_PLUCK = 16, EV_COLLAPSE = 17, EV_SINGULARITY = 18,
            EV_PEACE = 19, EV_EYE = 20, EV_WATCH = 21, EV_RETURN = 23,
            EV_CUT = 24, EV_LAYER = 25, EV_TURN = 26, EV_SPIN = 27, EV_SWALLOW = 28, EV_GLINT = 29;

    /**
     * Surfaces. A straight stroke from edge to edge cuts the world in two: the piece holding less
     * of the crystal falls away for good, and the cut face shows the next surface down. Surfaces
     * come round in order, each with its own colour and its own language, one for every verb:
     * clear hears where you look; lilac, violet and red hear which strings you pluck; light blue
     * and blue hear where you send hoppers under, as they spiral back up; lime hears the glints
     * you catch in a circle. A word is the side of the crystal and whether it came on the breath.
     */
    static final int LAYERS = 7;
    static final float[][] TINT = {
            {1f, 1f, 1f}, {0.8f, 0.58f, 1f}, {0.55f, 0.85f, 1f}, {0.7f, 1f, 0.42f},
            {0.62f, 0.36f, 1f}, {0.32f, 0.48f, 1f}, {1f, 0.34f, 0.32f}};
    int layer;
    /** The surfaces the world has heard a pattern on this cycle, one bit each. */
    int layersHeard;
    /** Seconds into a cut, or -1. */
    float cutAge = -1f;
    /**
     * The cut plays out in turns, seconds from the stroke: the world holds still while the
     * renderer takes its picture; the piece that goes tips over and falls; the piece that stays
     * turns on the cut as on a hinge until its cut face, the strata below, looks up; the next
     * surface spreads from the cut; the crystal rises through it.
     */
    static final float CUT_HOLD = 0.12f, CUT_FALL = 0.2f, CUT_TURN = 1.25f, CUT_WASH = 2.75f,
            CUT_RISE = 3.3f, CUT_TIME = 4.4f;
    /** The cut: a point on it and the normal towards the piece that falls away, space units. */
    float cutX, cutY, cutNX, cutNY;
    /** Whether the cut has taken effect: what was on the far side is gone, the next surface is up. */
    boolean cutDone;
    /** Set by the renderer once it holds the picture of the world whole; the cut waits for it. */
    volatile boolean pictured;
    private boolean cutSpent;

    static final int LOOKS = 0, STRINGS = 1, SPIRALS = 2, GLINTS = 3;

    /** The language a surface hears. */
    static int language(int layer) {
        switch (layer) {
            case 1: case 4: case 6: return STRINGS;
            case 2: case 5: return SPIRALS;
            case 3: return GLINTS;
            default: return LOOKS;
        }
    }

    static boolean listensToStrings(int layer) {
        return language(layer) == STRINGS;
    }

    /** Surfaces that must have sung back before a cycle can end. */
    static final int SURFACES_TO_END = 3;

    /**
     * Glints: on lime, between breaths, small lights tremble on the water here and there. A
     * circle drawn round one catches it, and the world hears where it was.
     */
    static final int MAX_GLINTS = 3;
    static final float GLINT_LIFE = 2.2f;
    final float[] gx = new float[MAX_GLINTS], gy = new float[MAX_GLINTS], gage = new float[MAX_GLINTS];
    final boolean[] gon = new boolean[MAX_GLINTS];
    private int glintBeat = -1;
    /** Index of the first glint in the last list drops() wrote. */
    int glintsFrom;

    /** Whether strings show on this surface: on clear ones and on those that listen to them. */
    boolean stringsShown() {
        return layer == 0 || listensToStrings(layer);
    }

    /**
     * The eye in the top right corner. Back opens it for a while; a touch on it hands the game
     * to the watcher. Back again while it is open leaves the game.
     */
    float eye;
    private float eyeLeft;
    static final float EYE_U = 0.86f, EYE_TOP = 0.13f, EYE_R = 0.055f;
    /**
     * The drop turned round. A stroke circling the drop spins it, and every turn makes it bigger
     * and darker; left alone it settles back. Turned far enough, it swells over everything and
     * closes into black: a new world, still and silent, and the game starts from the beginning.
     */
    static final float DROP_TURNS = 4f, SWALLOW_TIME = 3.5f;
    /** How far the drop has been turned, 0 to 1. */
    float dropGrow;
    /** The drop's own turn, radians. */
    float dropSpin;
    /** Seconds into the drop closing over the world, or -1. */
    float swallowAge = -1f;
    private float turnAngle;
    private boolean turnOn;
    private int turnQuarter;

    /**
     * Left alone, the drop a collapse leaves fades and is gone: the black of before the first
     * touch. It is the one ending reached by not touching. Everything starts over from it.
     */
    static final float DROP_WAIT = 15f, DROP_FADE = 3f;
    private float dropAge;
    /** How many times the world has gone back to black; every return grows a different crystal. */
    int epoch;

    /**
     * The end of a cycle. When the crystal is nearly all white and the water still, or when every
     * pit is full and the cluster can grow no more, it falls in on itself and leaves one drop.
     * A touch on the drop begins the next cycle, with the spectrum shifted.
     */
    int generation;
    /** Seconds since the collapse began, or -1. */
    float collapse = -1f;
    static final float COLLAPSE_TIME = 5.5f;
    /** The drop a collapse leaves behind, waiting; space units. */
    boolean dropLeft;
    float dropX, dropY;
    /** Weariness with patterns: every heard one adds to it, and it fades over minutes. */
    private float weary;

    /** How far each string swings, as a share of its length, keyed by the lower qubit of its pair. */
    final float[] strings = new float[Crystal.MAX_HOPPERS];
    /** A string at rest still trembles this much. */
    private static final float STRING_REST = 0.012f;
    /** Pitch of the string plucked last, for the sound. */
    float pluckPitch;

    /**
     * A circle drawn on the liquid holds for a while. A pair born inside it is observed by the
     * circle itself: it becomes real with no touch, cannot get out, and rings against the edge.
     * When the circle fades, both drops fall in together and settle as an entangled pair.
     */
    boolean ringOn;
    float ringX, ringY, ringR, ringAge;
    static final float RING_LIFE = 6f;
    private float ringKeep;
    /** The path of the current stroke, space units, to recognise a circle. */
    private final float[] pathX = new float[96], pathY = new float[96];
    private int pathN;
    /** Bounce pitch for the sound: the smaller the circle, the higher it rings. */
    float bounceSize;
    /** Id of the next entangled pair, and the qubit already seated for a pair still falling. */
    private int nextPair = 1;
    private final int[] seatedFor = new int[MAX_FLYERS];
    private final int[] seatedQubit = new int[MAX_FLYERS];

    // Visions of deep space that open through the liquid, each tied to an event.
    static final int WARP = 0, BLUE_GALAXY = 1, SUPERNOVA = 2, LAVA = 3, NEBULA = 4, STAR = 5, EDGE_GALAXY = 6;
    static final int VISIONS = 7;
    /** Seconds a vision lasts, and the quiet after one before another may open. */
    static final float VISION_LIFE = 7.5f, VISION_REST = 40f;
    /** The open vision, or -1. */
    int vision = -1;
    float visionAge;
    private float visionRest = 20f, visionIdle;
    private int missStreak, beatStreak;
    private float stroke;
    private boolean strokeSpent, chaosSeen;

    /**
     * Disorder in [0, 1]. It creeps up with time and with every qubit and hopper. High, it means
     * more pairs, a surface that ripples by itself, a string out of tune and a shimmering film.
     * A pattern the world hears brings it down.
     */
    float entropy = 0.1f;
    private static final float ENTROPY_DRIFT = 0.0008f, ENTROPY_QUBIT = 0.008f, ENTROPY_HOPPER = 0.004f;
    final Listener listener = new Listener();
    private float noise;

    // virtual pairs
    static final int MAX_PAIRS = 5;
    /** Lifetime of an unobserved pair, seconds. */
    private static final float PAIR_LIFE = 1.9f;
    /** Widest gap between the two drops of a pair, and the drop radius, in space units. */
    private static final float PAIR_GAP = 0.085f, DROP = 0.048f;
    /** A touch this close to a drop observes it. Generous: a fingertip is wide. */
    private static final float HIT = 0.11f;
    /** A touch on empty liquid unsettles pairs this close. */
    private static final float DECOHERE = 0.3f;
    /** Height a wave must pile up to before a pair can rise from it unprompted. */
    private static final float CREST = 0.12f;


    // real drops in flight
    static final int MAX_FLYERS = 12;
    private static final int FALLING = 0, ESCAPING = 1, TRAPPED = 2;

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
    /** Entangled pair a flyer belongs to, or 0. */
    private final int[] fpair = new int[MAX_FLYERS];
    private final float[] fbounce = new float[MAX_FLYERS];
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
        layersHeard = 0;
        crystal = new Crystal(seed + generation * 7919L + epoch * 104729L);
        dropLeft = false;
        mid[0] = 0f; mid[1] = 0.5f; mid[2] = 0f;
        across = 1.2f;
        upward = 1.2f;
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
    void restore(int gen, int savedEpoch, float u, float v, int settled, float grown, float[] hues, int[] partners, float savedEntropy,
                 float savedPool, float savedResonance, float away, int savedLayer, int savedHeard, int[] lostAt,
                 float[] cuts, int[] cutAt) {
        generation = gen;
        epoch = savedEpoch;
        plant(u, v);
        layer = Math.max(0, Math.min(LAYERS - 1, savedLayer));
        layersHeard = savedHeard;
        crystal.restore(settled, grown, hues, partners, lostAt, cuts, cutAt);
        entropy = Math.max(0f, Math.min(1f, savedEntropy));
        pool = savedPool + Math.min(AWAY_CAP, Math.max(0f, away) * TRICKLE);
        resonance = savedResonance;
        crystal.middle(mid);
        crystal.reach(mid[0], mid[1], mid[2], ext);
        across = ext[0];
        upward = ext[1];
        measureBox();
    }

    /** A saved game between cycles: the drop of the last collapse waits in the middle. */
    void between(int gen, int savedEpoch, float savedResonance, int savedLayer) {
        layer = Math.max(0, Math.min(LAYERS - 1, savedLayer));
        generation = gen;
        epoch = savedEpoch;
        resonance = savedResonance;
        dropLeft = gen > 0;
        dropX = 0.5f;
        dropY = 0.52f * aspect;
    }

    /** Shift of every film thickness for this cycle, so each cycle has its own colours. */
    float filmShift() {
        return (generation * 97) % 360;
    }

    /** How far the collapse has gone, 0 to 1; 0 when there is none. */
    float collapseLevel() {
        return collapse < 0f ? 0f : Math.min(1f, collapse / COLLAPSE_TIME);
    }

    /** The watcher, while it holds the game; null when the player does. */
    Watcher watcher;

    /** A finger came down at (u, v). Ignored while the watcher plays. */
    void touch(float u, float v) {
        if (watcher != null || swallowAge >= 0f) return;
        if (eye > 0.5f && onEye(u, v)) {
            watcher = new Watcher(this, seed ^ (long) (time * 1000f));
            eyeLeft = 0f;
            event(EV_WATCH);
            return;
        }
        touchAs(u, v);
    }

    private boolean onEye(float u, float v) {
        float dx = u - EYE_U, dy = (v - eyeV()) * aspect;
        return dx * dx + dy * dy < (EYE_R * 1.8f) * (EYE_R * 1.8f);
    }

    /** Height of the eye's centre on screen, in screen units. */
    float eyeV() {
        return 1f - EYE_TOP / aspect;
    }

    /**
     * The system back. Opens the eye; with the eye open, asks to leave; while the watcher plays,
     * takes the game back from it. Returns true when the game should close.
     */
    boolean back() {
        if (watcher != null && swallowAge < 0f) {
            watcher = null;
            event(EV_RETURN);
            return false;
        }
        if (eye > 0.5f && watcher == null) return true;
        eyeLeft = 6f;
        event(EV_EYE);
        return false;
    }

    /** How much faster the world runs. The watcher plays at a person's pace, so: never. */
    float speed() {
        return 1f;
    }

    /**
     * Stars in the deep, one for every cycle that has ended in a collapse: warm for the player's,
     * colder and smaller for the watcher's. They outlast everything, black peace included.
     */
    int played, watched;
    static final int MAX_STARS = 120;

    /**
     * Where star k of a kind sits on screen, written into out as u, v. Fixed for each star, so
     * the sky only ever gains points; kept clear of the edges and the eye's corner.
     */
    void star(int k, boolean byWatcher, float[] out) {
        long h = (k * 0x9E3779B97F4A7C15L) ^ (byWatcher ? 0x5851F42D4C957F2DL : 0x2545F4914F6CDD1DL);
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        float u = 0.06f + 0.88f * ((h & 0xFFFF) / 65535f);
        float v = 0.05f + 0.85f * (((h >>> 16) & 0xFFFF) / 65535f);
        if (u > 0.74f && v > 0.8f) v -= 0.25f;   // the eye's corner stays dark
        out[0] = u;
        out[1] = v;
    }

    /** A finger slid across the glass. Ignored while the watcher plays. */
    void drag(float u, float v) {
        if (watcher != null) return;
        dragAs(u, v);
    }

    /** A touch, whoever makes it. */
    void touchAs(float u, float v) {
        if (collapse >= 0f || cutAge >= 0f) return;
        cutSpent = false;
        stroke = 0f;
        strokeSpent = false;
        pathN = 0;
        addPath(u, v * aspect);
        liquid.press(u, v, 2.6f, 1.1f);
        turnOn = false;
        if (crystal == null) {
            if (swallowAge >= 0f) return;
            // after a collapse the new cycle grows from the drop that was left, touched on it;
            // a touch beside it may be the start of a stroke round it
            if (dropLeft) {
                float dx = u - dropX, dy = v * aspect - dropY, reach = dropRadius() * 1.6f + 0.06f;
                if (dx * dx + dy * dy > reach * reach) return;
                u = dropX;
                v = dropY / aspect;
            }
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
        else if (!pluckAt(u, v) && !sinkAt(u, v)) miss(x, y);
    }

    /** A stroke, whoever makes it: ripples, and a long stroke may open a vision. */
    void dragAs(float u, float v) {
        if (cutAge >= 0f) return;
        liquid.press(u, v, 2.2f, 0.35f);
        stroke += 0.02f;
        addPath(u, v * aspect);
        if (crystal == null) {
            if (dropLeft && swallowAge < 0f) turnDrop(u, v * aspect);
            return;
        }
        if (!cutSpent && cutsAcross()) return;
        if (closesCircle()) {
            strokeSpent = true;
            pathN = 0;
            return;
        }
    }

    /** Radius of the drop a collapse leaves, as a share of screen width, before it swallows. */
    float dropRadius() {
        return DROP * 1.4f * (1f + 3f * dropGrow);
    }

    /** A stroke round the drop turns it; a full turn's worth of them makes it a quarter bigger. */
    private void turnDrop(float x, float y) {
        float dx = x - dropX, dy = y - dropY;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < dropRadius() * 1.05f || d > 0.9f) {
            turnOn = false;
            return;
        }
        float a = (float) Math.atan2(dy, dx);
        if (turnOn) {
            float da = a - turnAngle;
            if (da > Math.PI) da -= 2f * (float) Math.PI;
            if (da < -Math.PI) da += 2f * (float) Math.PI;
            if (Math.abs(da) < 1.2f) {
                dropGrow = Math.min(1f, dropGrow + Math.abs(da) / (2f * (float) Math.PI * DROP_TURNS));
                dropSpin += da;
                dropAge = 0f;
                int q = (int) (dropGrow * DROP_TURNS * 4f);
                if (q != turnQuarter) {
                    turnQuarter = q;
                    event(EV_SPIN);
                }
                if (dropGrow >= 1f) {
                    swallowAge = 0f;
                    event(EV_SWALLOW);
                }
            }
        }
        turnAngle = a;
        turnOn = true;
    }

    private boolean nearEdge(float x, float y) {
        return x < 0.07f || x > 0.93f || y < 0.07f || y > aspect - 0.07f;
    }

    /**
     * Whether the stroke has just crossed the world from one edge to another in a straight line.
     * If so, the world is cut along it.
     */
    private boolean cutsAcross() {
        if (pathN < 8) return false;
        float ax = pathX[0], ay = pathY[0], bx = pathX[pathN - 1], by = pathY[pathN - 1];
        if (!nearEdge(ax, ay) || !nearEdge(bx, by)) return false;
        float lx = bx - ax, ly = by - ay, len = (float) Math.sqrt(lx * lx + ly * ly);
        if (len < 0.8f) return false;
        float nx = -ly / len, ny = lx / len;
        for (int i = 0; i < pathN; i++)
            if (Math.abs((pathX[i] - ax) * nx + (pathY[i] - ay) * ny) > 0.06f) return false;
        cutSpent = true;
        strokeSpent = true;
        cut(ax, ay, nx, ny);
        return true;
    }

    /**
     * Cuts the world along the line through (x, y) with normal (nx, ny). The piece holding less
     * of the crystal goes; with no crystal on either side, the smaller one. The world holds
     * still for a moment first, so the picture of it whole can be taken.
     */
    private void cut(float x, float y, float nx, float ny) {
        camera(cMvp, cModel, cEye, cShift);
        float plus = 0f, minus = 0f;
        for (int i = 0; i < crystal.hoppers.size(); i++) {
            Crystal.Hopper h = crystal.hoppers.get(i);
            if (h.lost) continue;
            float[] c = project(h.cx(), h.cy(), h.cz());
            if (c == null) continue;
            float m = h.size * h.size * Crystal.ease(h.grown);
            if ((c[0] - x) * nx + (c[1] * aspect - y) * ny > 0f) plus += m;
            else minus += m;
        }
        boolean flip;
        if (Math.abs(plus - minus) > 1e-3f) {
            flip = plus > minus;
        } else {
            // no crystal to weigh: count a grid of points over the screen
            int more = 0, all = 0;
            for (int i = 0; i < 24; i++)
                for (int j = 0; j < 48; j++) {
                    float px = (i + 0.5f) / 24f, py = (j + 0.5f) / 48f * aspect;
                    if ((px - x) * nx + (py - y) * ny > 0f) more++;
                    all++;
                }
            flip = more * 2 > all;
        }
        if (flip) { nx = -nx; ny = -ny; }
        cutX = x;
        cutY = y;
        cutNX = nx;
        cutNY = ny;
        cutAge = 0f;
        cutDone = false;
        pictured = false;
        event(EV_CUT);
    }

    /** The cut takes effect: what was in the falling piece goes with it, the crystal too. */
    private void cutThrough() {
        cutDone = true;
        float x = cutX, y = cutY, nx = cutNX, ny = cutNY;
        for (int i = 0; i < MAX_PAIRS; i++)
            if (pon[i] && (px[i] - x) * nx + (py[i] - y) * ny > 0f) pon[i] = false;
        for (int j = 0; j < MAX_FLYERS; j++)
            if (fon[j] && (fx[j] - x) * nx + (fy[j] - y) * ny > 0f) fon[j] = false;
        ringOn = false;
        for (int g = 0; g < MAX_GLINTS; g++) gon[g] = false;
        // The line on the glass is a plane through the eye. On screen a point of the cluster is
        // at u = (X / W + shift.x) / 2 + 1 / 2, v likewise, where X, Y, W are rows of the camera
        // matrix applied to it; the side test, times W, is linear in the point.
        camera(cMvp, cModel, cEye, cShift);
        float ka = 0.5f * nx, kb = 0.5f * aspect * ny;
        float kw = (0.5f * cShift[0] + 0.5f - x) * nx + (0.5f * cShift[1] + 0.5f) * aspect * ny - y * ny;
        float[] plane = new float[4];
        for (int c = 0; c < 4; c++)
            plane[c] = ka * cMvp[c * 4] + kb * cMvp[c * 4 + 1] + kw * cMvp[c * 4 + 3];
        float len = (float) Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2]);
        if (len > 1e-6f) crystal.cutAlong(plane[0] / len, plane[1] / len, plane[2] / len, plane[3] / len);
        listener.reset();
        // the next surface is up from now on; nobody sees it until the turn shows it
        layer = (layer + 1) % LAYERS;
        liquid.calm(0f);
        boxAge = 1f;
    }

    /**
     * How far from the cut the next surface has spread, space units, at a given moment of the
     * cut: nothing until the strata face up, then running out fast and slowing, past every corner.
     */
    static float washReach(float age) {
        if (age < CUT_WASH) return 0f;
        return 3f * (1f - (float) Math.exp(-(age - CUT_WASH) * 1.1f));
    }

    /** How dark the drop a collapse leaves has turned: the black world growing inside it. */
    float dropDark() {
        if (!dropLeft || crystal != null) return 0f;
        float k = swallowAge >= 0f ? Math.min(1f, swallowAge / (SWALLOW_TIME * 0.6f)) : 0f;
        return Math.min(1f, 0.55f * dropGrow + k);
    }

    /** How far under the surface the crystal is while it rises after a cut: 1 under, 0 up. */
    float cutSink() {
        // until the cut takes effect the crystal stands as it was: that is the picture taken
        if (cutAge < 0f || !cutDone) return 0f;
        float k = Math.max(0f, Math.min(1f, (cutAge - CUT_RISE) / (CUT_TIME - CUT_RISE)));
        float a = 1f - k;
        return a * a * (3f - 2f * a);
    }

    /** How deep the crystal goes for its rise, cluster units: deep enough to be gone. */
    float sinkDepth() {
        return 2.2f * upward;
    }

    /** RGB of the current surface, for drops and reflections. */
    float[] tint() {
        return TINT[layer];
    }

    /** How far into the current cut, 0 to 1; 0 with none. */
    float cutLevel() {
        return cutAge < 0f ? 0f : Math.min(1f, cutAge / CUT_TIME);
    }

    private void addPath(float x, float y) {
        if (pathN == pathX.length) {
            System.arraycopy(pathX, 1, pathX, 0, pathN - 1);
            System.arraycopy(pathY, 1, pathY, 0, pathN - 1);
            pathN--;
        }
        pathX[pathN] = x;
        pathY[pathN] = y;
        pathN++;
    }

    /**
     * Whether the stroke has just come back round to where it began, roughly as a circle.
     * If so, the circle starts to hold.
     */
    private boolean closesCircle() {
        if (pathN < 12) return false;
        float ex = pathX[pathN - 1], ey = pathY[pathN - 1];
        // look for the start of the loop: an earlier point the stroke has come back to
        for (int start = 0; start < pathN - 11; start++) {
            float dx = ex - pathX[start], dy = ey - pathY[start];
            if (dx * dx + dy * dy > 0.06f * 0.06f) continue;
            int n = pathN - start;
            float cx = 0, cy = 0;
            for (int i = start; i < pathN; i++) { cx += pathX[i]; cy += pathY[i]; }
            cx /= n;
            cy /= n;
            float mean = 0, sq = 0;
            for (int i = start; i < pathN; i++) {
                float r = (float) Math.hypot(pathX[i] - cx, pathY[i] - cy);
                mean += r;
                sq += r * r;
            }
            mean /= n;
            float spread = (float) Math.sqrt(Math.max(0f, sq / n - mean * mean)) / mean;
            if (mean < 0.07f || mean > 0.4f || spread > 0.28f) continue;
            ringOn = true;
            ringX = cx;
            ringY = cy;
            ringR = mean;
            ringAge = 0f;
            ringKeep = 0f;
            event(EV_RING);
            catchGlints(cx, cy, mean);
            return true;
        }
        return false;
    }

    /** On lime, a circle closed round glints catches them, and the world hears where they were. */
    private void catchGlints(float cx, float cy, float r) {
        if (language(layer) != GLINTS || crystal == null) return;
        boolean onBeat = Math.abs(fromBeat()) <= WINDOW;
        for (int g = 0; g < MAX_GLINTS; g++) {
            if (!gon[g]) continue;
            float dx = gx[g] - cx, dy = gy[g] - cy;
            if (dx * dx + dy * dy > r * r * 1.1f) continue;
            gon[g] = false;
            liquid.press(gx[g], gy[g] / aspect, 2f, 0.4f);
            event(EV_GLINT);
            int n = listener.hear(Listener.symbol(quarter(gx[g] - crystalX(), gy[g] - crystalY()), onBeat), time);
            if (n > 0) heard(n);
        }
    }

    /** Between breaths on lime, glints light up on the water; each trembles a while and goes out. */
    private void updateGlints(float dt) {
        for (int g = 0; g < MAX_GLINTS; g++) {
            if (!gon[g]) continue;
            gage[g] += dt;
            if (gage[g] >= GLINT_LIFE) gon[g] = false;
        }
        if (language(layer) != GLINTS) return;
        // halfway between two breaths, once a breath
        int beat = (int) Math.floor((time - beatStart) / BEAT + 0.5f);
        if (fromBeat() < BEAT * 0.3f || beat == glintBeat) return;
        glintBeat = beat;
        int want = 1 + (rnd.nextFloat() < 0.4f ? 1 : 0);
        for (int g = 0; g < MAX_GLINTS && want > 0; g++) {
            if (gon[g]) continue;
            for (int tries = 0; tries < 12; tries++) {
                float x = 0.12f + 0.76f * rnd.nextFloat(), y = (0.1f + 0.8f * rnd.nextFloat()) * aspect;
                if (!roomFor(x, y)) continue;
                gx[g] = x;
                gy[g] = y;
                gage[g] = 0f;
                gon[g] = true;
                want--;
                break;
            }
        }
    }

    /** A touch on a string plucks it; it sounds lower the longer it is. */
    private boolean pluckAt(float u, float v) {
        if (crystal == null || !stringsShown()) return false;
        float[] a = new float[3], b = new float[3];
        int best = -1;
        float bestD = 0.045f, bestLen = 1f;
        for (int i = 0; i < crystal.qubits; i++) {
            if (!crystal.stringShown(i)) continue;
            crystal.stringEnds(i, a, b);
            float[] pa = project(a[0], a[1], a[2]), pb = project(b[0], b[1], b[2]);
            if (pa == null || pb == null) continue;
            // distance from the finger to the string on screen, in width units
            float ax = pa[0], ay = pa[1] * aspect, bx = pb[0], by = pb[1] * aspect, px = u, py = v * aspect;
            float ex = bx - ax, ey = by - ay, l2 = ex * ex + ey * ey + 1e-6f;
            float t = Math.max(0f, Math.min(1f, ((px - ax) * ex + (py - ay) * ey) / l2));
            float qx = ax + ex * t - px, qy = ay + ey * t - py;
            float d = (float) Math.sqrt(qx * qx + qy * qy);
            if (d < bestD && t > 0.08f && t < 0.92f) {
                bestD = d;
                best = i;
                float lx = b[0] - a[0], ly = b[1] - a[1], lz = b[2] - a[2];
                bestLen = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
            }
        }
        if (best < 0) return false;
        strings[best] = Math.max(strings[best], 0.14f);
        pluckPitch = Math.max(110f, Math.min(880f, 330f * 1.6f / Math.max(0.2f, bestLen)));
        event(EV_PLUCK);
        // surfaces that listen to strings hear which string, if it was plucked on the breath
        if (listensToStrings(layer) && Math.abs(fromBeat()) <= WINDOW) {
            int n = listener.hear(best % Listener.SYMBOLS, time);
            if (n > 0) heard(n);
        }
        return true;
    }

    /** A touch on a hopper sends it under the black surface. */
    private boolean sinkAt(float u, float v) {
        if (crystal == null) return false;
        int best = -1;
        float bestW = 1e9f;
        for (int i = 0; i < crystal.hoppers.size(); i++) {
            Crystal.Hopper h = crystal.hoppers.get(i);
            if (h == crystal.active || h.under || h.sink > 0f || h.lost) continue;
            float[] c = project(h.cx(), h.cy(), h.cz());
            float[] e = project(h.cx() + h.size, h.cy(), h.cz());
            if (c == null || e == null) continue;
            float reach = Math.max(0.05f, Math.abs(e[0] - c[0]) * 1.1f);
            float dx = c[0] - u, dy = (c[1] - v) * aspect;
            // of the hoppers under the finger, the nearest to the eye
            if (dx * dx + dy * dy < reach * reach && c[2] < bestW) {
                bestW = c[2];
                best = i;
            }
        }
        if (best < 0 || !crystal.submerge(best, spin)) return false;
        liquid.press(u, v, 3.5f, 1.4f);
        event(EV_SINK);
        maybeVision(LAVA, 0.5f);
        if (language(layer) == SPIRALS) {
            // light blue and blue hear where a hopper went under; an entangled partner goes
            // with it at once, and is heard too
            boolean onBeat = Math.abs(fromBeat()) <= WINDOW;
            int n = listener.hear(Listener.symbol(sideOf(best), onBeat), time);
            if (n > 0) heard(n);
            else if (crystal.entangled(best)) {
                n = listener.hear(Listener.symbol(sideOf(crystal.partner[best]), onBeat), time);
                if (n > 0) heard(n);
            }
        }
        return true;
    }

    /** Which side of the crystal hopper i shows on, as the world hears it. */
    int sideOf(int i) {
        Crystal.Hopper h = crystal.hoppers.get(i);
        float[] c = project(h.cx(), h.cy(), h.cz());
        if (c == null) return 0;
        return quarter(c[0] - crystalX(), c[1] * aspect - crystalY());
    }

    /** The side of the crystal for an offset from its middle: 0 above, 1 right, 2 below, 3 left. */
    static int quarter(float sx, float sy) {
        return Math.abs(sx) > Math.abs(sy) ? (sx > 0 ? 1 : 3) : (sy > 0 ? 0 : 2);
    }

    /** Screen position of a point of the cluster: u, v and the depth term w; null if behind. */
    float[] project(float x, float y, float z) {
        float cx = cMvp[0] * x + cMvp[4] * y + cMvp[8] * z + cMvp[12];
        float cy = cMvp[1] * x + cMvp[5] * y + cMvp[9] * z + cMvp[13];
        float cw = cMvp[3] * x + cMvp[7] * y + cMvp[11] * z + cMvp[15];
        if (cw <= 1e-4f) return null;
        return new float[] {(cx / cw + cShift[0]) * 0.5f + 0.5f, (cy / cw + cShift[1]) * 0.5f + 0.5f, cw};
    }

    /** Opens a vision with the given chance, unless one is open or the last was too recent. */
    private void maybeVision(int which, float chance) {
        if (vision >= 0 || visionRest > 0f || rnd.nextFloat() >= chance) return;
        vision = which;
        visionAge = 0f;
        visionIdle = 0f;
        event(EV_VISTA);
    }

    /** How strongly the open vision shows: it swells in and fades out. */
    float visionAlpha() {
        if (vision < 0) return 0f;
        float in = Math.min(1f, visionAge / 1.4f), out = Math.min(1f, (VISION_LIFE - visionAge) / 2.2f);
        float a = Math.max(0f, Math.min(in, out));
        return a * a * (3f - 2f * a);
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
        // which side of the crystal the looked-at drop was on, as the world hears it
        float sx = (side > 0 ? ax : bx) - cx, sy = (side > 0 ? ay : by) - cy;
        int quarter = Math.abs(sx) > Math.abs(sy) ? (sx > 0 ? 1 : 3) : (sy > 0 ? 0 : 2);
        launch(aFalls ? ax : bx, aFalls ? ay : by, FALLING, FALL_ENERGY * k, pfilm[i]);
        launch(aFalls ? bx : ax, aFalls ? by : ay, ESCAPING, ESCAPE_RESONANCE * k, pfilm[i]);
        resonance += ESCAPE_RESONANCE * k;
        if (onBeat) flash = Math.max(flash, 0.8f);
        event(onBeat ? EV_OBSERVE_ON_BEAT : EV_OBSERVE);
        missStreak = 0;
        beatStreak = onBeat ? beatStreak + 1 : 0;
        if (beatStreak >= 4) {
            beatStreak = 0;
            maybeVision(STAR, 0.7f);
        }
        if (language(layer) == LOOKS) {
            int n = listener.hear(Listener.symbol(quarter, onBeat), time);
            if (n > 0) heard(n);
        }
    }

    /**
     * A pattern was heard: the newest qubits fold into one white one, disorder drops by more
     * for a longer pattern, and the surface stills.
     */
    private void heard(int n) {
        // order is paid for with material: only what is actually folded into white counts,
        // and a world that has heard many patterns lately is harder to impress
        int fused = crystal.fuse(n);
        float worth = 0.1f * fused / (1f + 0.6f * weary);
        weary += 1f;
        entropy = Math.max(0f, entropy - worth);
        layersHeard |= 1 << layer;
        event(EV_PATTERN);
        if (fused > 0) {
            liquid.calm(0.35f);
            flash = 1f;
            maybeVision(listensToStrings(layer) ? EDGE_GALAXY : WARP, 1f);
        }
    }

    /** The drop is gone. Nothing is left, not even the count of cycles. */
    private void peace() {
        startOver();
        liquid.press(dropX, dropY / aspect, 2f, 0.25f);
        event(EV_PEACE);
    }

    /**
     * The drop has closed over everything: a new world, black and still, and not a sound.
     * The game starts from the beginning; the eye, if it played, gives the game back.
     */
    private void swallowed() {
        startOver();
        liquid.calm(0f);
        if (watcher != null) {
            watcher = null;
            event(EV_RETURN);
        }
    }

    private void startOver() {
        dropLeft = false;
        dropGrow = 0f;
        dropSpin = 0f;
        turnQuarter = 0;
        turnOn = false;
        swallowAge = -1f;
        layer = 0;
        generation = 0;
        resonance = 0f;
        entropy = 0.1f;
        epoch++;
    }

    /** Whether the cycle is over: a white crust on still water, or a cluster with every pit full. */
    private boolean collapseDue() {
        // a cycle cannot end until the world has heard you on three different surfaces, the one
        // under you among them
        if (Integer.bitCount(layersHeard) < SURFACES_TO_END || (layersHeard & (1 << layer)) == 0) return false;
        int real = 0, white = 0, standing = 0;
        for (int i = 0; i < crystal.qubits; i++) {
            if (!crystal.realQubit(i)) continue;
            real++;
            if (crystal.hues[i] > 1.5f) white++;
        }
        for (int i = 0; i < crystal.hoppers.size(); i++) {
            Crystal.Hopper h = crystal.hoppers.get(i);
            if (!h.lost && h != crystal.active) standing++;
        }
        boolean order = real >= 10 && white >= real * 0.5f && entropy < 0.1f;
        boolean full = crystal.active == null && standing >= 12 && real >= standing;
        return order || full;
    }

    private void beginCollapse() {
        collapse = 0f;
        for (int g = 0; g < MAX_GLINTS; g++) gon[g] = false;
        for (int i = 0; i < MAX_PAIRS; i++) pon[i] = false;
        for (int j = 0; j < MAX_FLYERS; j++) fon[j] = false;
        ringOn = false;
        event(EV_COLLAPSE);
    }

    private void endCollapse() {
        if (watcher != null) watched++;
        else played++;
        dropX = crystalX();
        dropY = crystalY();
        dropLeft = true;
        dropGrow = 0f;
        dropSpin = 0f;
        turnQuarter = 0;
        dropAge = 0f;
        crystal = null;
        collapse = -1f;
        generation++;
        entropy = 0.05f;
        pool = 0f;
        weary = 0f;
        for (int i = 0; i < strings.length; i++) strings[i] = 0f;
        liquid.calm(0.1f);
        liquid.press(dropX, dropY / aspect, 6f, 2.2f);
        flash = 1f;
        event(EV_SINGULARITY);
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
        beatStreak = 0;
        if (++missStreak >= 3) {
            missStreak = 0;
            maybeVision(NEBULA, 0.6f);
        }
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
            fpair[j] = 0;
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
        // the eye stays open while it watches; otherwise it closes by itself
        eyeLeft = Math.max(0f, eyeLeft - dt / speed());
        float eyeTarget = watcher != null || eyeLeft > 0f ? 1f : 0f;
        eye += (eyeTarget - eye) * Math.min(1f, dt * 4f / speed());
        if (watcher != null) watcher.step(dt);
        weary *= (float) Math.exp(-dt / 150f);
        if (crystal == null) {
            if (swallowAge >= 0f) {
                swallowAge += dt;
                if (swallowAge >= SWALLOW_TIME) swallowed();
            } else if (dropLeft) {
                // let go, the drop settles back slowly; untouched long enough, it melts away
                dropGrow = Math.max(0f, dropGrow - dt * 0.03f);
                dropAge += dt;
                if (dropAge >= DROP_WAIT) peace();
            }
            return;
        }

        if (cutAge >= 0f) {
            // the world holds still while it is cut; then the next surface is there
            float before = cutAge;
            cutAge += dt;
            if (!cutDone && cutAge >= CUT_HOLD && (pictured || cutAge >= 0.5f)) cutThrough();
            if (before < CUT_TURN && cutAge >= CUT_TURN) event(EV_TURN);
            if (before < CUT_WASH && cutAge >= CUT_WASH) event(EV_LAYER);
            if (cutAge >= CUT_TIME) {
                cutAge = -1f;
                if (!cutDone) cutThrough();
                measureBox();
            }
            return;
        }
        if (collapse >= 0f) {
            // falling in: the turn quickens, the surface is drawn towards the middle
            collapse += dt;
            float k = collapseLevel();
            spin += dt * (0.11f + 3f * k * k);
            pulse = k;
            if (rnd.nextFloat() < dt * 6f)
                liquid.press(crystalU(), crystalV(), 3f + 4f * (1f - k), -0.5f * k);
            if (collapse >= COLLAPSE_TIME) endCollapse();
            return;
        }
        if (collapseDue()) {
            beginCollapse();
            return;
        }

        float d = fromBeat();
        pulse = (float) Math.exp(-(d * d) / 0.09f);
        int beat = (int) Math.floor((time - beatStart) / BEAT + 0.5f);
        if (beat != lastBeat && d >= 0f) {
            lastBeat = beat;
            liquid.press(crystalU(), crystalV(), 5f, 0.3f);
            event(EV_BREATH);
            for (int i = 0; i < strings.length; i++) strings[i] += 0.025f;
            int n = entropy > 0.55f ? 2 : 1;
            for (int i = 0; i < n; i++) birth(true);
        }

        float fade = (float) Math.exp(-dt * 0.8f);
        for (int i = 0; i < strings.length; i++) strings[i] = Math.max(STRING_REST, strings[i] * fade);

        entropy = Math.min(1f, entropy + ENTROPY_DRIFT * dt);
        if (entropy >= 0.95f && !chaosSeen) {
            chaosSeen = true;
            maybeVision(SUPERNOVA, 1f);
        } else if (entropy < 0.8f) {
            chaosSeen = false;
        }

        if (vision >= 0) {
            visionAge += dt;
            if (visionAge >= VISION_LIFE) {
                vision = -1;
                visionRest = VISION_REST;
            }
        } else {
            visionRest = Math.max(0f, visionRest - dt);
            // now and then a vision opens for no reason at all
            visionIdle += dt;
            if (visionIdle > 120f) {
                visionIdle = 0f;
                maybeVision(rnd.nextInt(VISIONS), 0.35f);
            }
        }

        // on the surfaces that hear sinking, hoppers spiral back up sooner: a word needs them
        int rose = crystal.updateSinking(dt, spin, language(layer) == SPIRALS ? 0.7f : (float) Math.PI);
        if (rose >= 0) {
            Crystal.Hopper h = crystal.hoppers.get(rose);
            float[] c = project(h.cx(), h.cy(), h.cz());
            if (c != null) liquid.press(c[0], c[1], 4f, -1.0f);
            event(EV_SURFACE);
            maybeVision(BLUE_GALAXY, 0.5f);
        }
        // disorder makes the surface tremble by itself
        noise += dt * 4f * entropy;
        while (noise >= 1f) {
            noise -= 1f;
            liquid.press(0.05f + 0.9f * rnd.nextFloat(), 0.05f + 0.9f * rnd.nextFloat(), 2f, 0.12f * entropy);
        }

        spontaneous += dt * (0.08f + 0.45f * entropy);
        if (spontaneous >= 1f) {
            spontaneous -= 1f;
            if (rnd.nextFloat() < 0.8f) birth(false);
        }

        if (ringOn) {
            ringAge += dt;
            ringKeep += dt;
            if (ringKeep > 0.45f && ringAge < RING_LIFE - 0.8f) {
                // keep the trace on the water: a faint touch round the circle
                ringKeep = 0f;
                for (int k = 0; k < 12; k++) {
                    float a = k * 0.5236f + ringAge;
                    liquid.press(ringX + (float) Math.cos(a) * ringR, (ringY + (float) Math.sin(a) * ringR) / aspect, 1.5f, 0.12f);
                }
            }
            if (ringAge >= RING_LIFE) ringOn = false;
        }

        updatePairs(dt);
        updateFlyers(dt);
        updateGlints(dt);

        pool += TRICKLE * dt;
        float use = Math.min(pool, GROWTH_RATE * dt);
        int before = crystal.settledVersion;
        float left = crystal.feed(use);
        pool -= use - left;
        if (crystal.settledVersion != before) {
            entropy = Math.min(1f, entropy + ENTROPY_HOPPER);
            event(EV_SETTLED);
        }

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
            if (g <= 0f || h.lost) continue;
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
        // order keeps the vacuum quiet: few pairs at once while disorder is low
        int alive = 0, limit = 1 + Math.round(3f * entropy);
        for (int i = 0; i < MAX_PAIRS; i++) if (pon[i]) alive++;
        if (alive >= limit) return;
        int slot = -1;
        for (int i = 0; i < MAX_PAIRS; i++) if (!pon[i]) { slot = i; break; }
        if (slot < 0) return;
        float x = 0, y = 0;
        boolean found = false;
        if (ringOn && rnd.nextFloat() < 0.6f) {
            // by chance, inside the circle
            float a = rnd.nextFloat() * 6.2832f, r = ringR * 0.45f * rnd.nextFloat();
            x = ringX + (float) Math.cos(a) * r;
            y = ringY + (float) Math.sin(a) * r;
            found = true;
        }
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
        if (ringOn && Math.hypot(x - ringX, y - ringY) < ringR * 0.7f) trap(slot);
    }

    /** The circle observes a pair born inside it: both drops become real and stay in. */
    private void trap(int i) {
        pon[i] = false;
        int id = nextPair++;
        for (int s = -1; s <= 1; s += 2) {
            for (int j = 0; j < MAX_FLYERS; j++) {
                if (fon[j]) continue;
                fon[j] = true;
                fkind[j] = TRAPPED;
                fx[j] = px[i] + pax[i] * 0.02f * s;
                fy[j] = py[i] + pay[i] * 0.02f * s;
                fvx[j] = pax[i] * 0.32f * s;
                fvy[j] = pay[i] * 0.32f * s;
                fval[j] = FALL_ENERGY;
                fage[j] = 0f;
                ffilm[j] = pfilm[i];
                fpair[j] = id;
                fbounce[j] = 0f;
                break;
            }
        }
        flash = Math.max(flash, 0.5f);
    }

    private boolean roomFor(float x, float y) {
        if (x < 0.08f || x > 0.92f || y < 0.08f || y > aspect - 0.08f) return false;
        if (x > box[0] - 0.08f && x < box[2] + 0.08f && y > box[1] - 0.08f && y < box[3] + 0.08f) return false;
        for (int i = 0; i < MAX_PAIRS; i++) {
            if (!pon[i]) continue;
            float dx = px[i] - x, dy = py[i] - y;
            if (dx * dx + dy * dy < 0.2f * 0.2f) return false;
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
                    if (fpair[j] > 0) {
                        // an entangled drop takes a pit if one is free, else it binds a qubit already there
                        int q = -1;
                        if (crystal.seat()) {
                            entropy = Math.min(1f, entropy + ENTROPY_QUBIT);
                            q = crystal.qubits - 1;
                        } else {
                            q = freeQubit(fpair[j]);
                        }
                        if (q >= 0 && joinPair(fpair[j], q)) {
                            int low = Math.min(q, crystal.partner[q]);
                            if (low >= 0) strings[low] = 0.18f;
                            event(EV_ENTANGLE);
                        }
                        else event(q >= 0 ? EV_QUBIT : EV_ABSORB);
                    } else if (crystal.seat()) {
                        entropy = Math.min(1f, entropy + ENTROPY_QUBIT);
                        event(EV_QUBIT);
                    } else {
                        event(EV_ABSORB);
                    }
                    continue;
                }
            } else if (fkind[j] == TRAPPED) {
                // inside the circle: bounce off its edge, ringing, until the circle fades
                if (!ringOn) {
                    fkind[j] = FALLING;
                    fage[j] = 0f;
                    float t = 0.15f;
                    fvx[j] = -dy / d * t;
                    fvy[j] = dx / d * t;
                    continue;
                }
                fbounce[j] -= dt;
                float rx = fx[j] - ringX, ry = fy[j] - ringY;
                float r = (float) Math.sqrt(rx * rx + ry * ry) + 1e-4f, edge = ringR * 0.82f;
                if (r > edge) {
                    float nx = rx / r, ny = ry / r, dot = fvx[j] * nx + fvy[j] * ny;
                    if (dot > 0f) {
                        fvx[j] -= 2f * dot * nx;
                        fvy[j] -= 2f * dot * ny;
                        fx[j] = ringX + nx * edge;
                        fy[j] = ringY + ny * edge;
                        if (fbounce[j] <= 0f) {
                            fbounce[j] = 0.15f;
                            resonance += 0.25f;
                            bounceSize = ringR;
                            liquid.press(fx[j], fy[j] / aspect, 1.4f, 0.25f);
                            event(EV_BOUNCE);
                        }
                    }
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

    /** A seated qubit with no partner yet, other than one already taken by this pair; -1 if none. */
    private int freeQubit(int id) {
        int taken = -1;
        for (int k = 0; k < MAX_FLYERS; k++) if (seatedFor[k] == id) taken = seatedQubit[k];
        int n = crystal.qubits;
        if (n == 0) return -1;
        int start = rnd.nextInt(n);
        for (int k = 0; k < n; k++) {
            int q = (start + k) % n;
            if (q != taken && crystal.partner[q] < 0) return q;
        }
        return -1;
    }

    /** Seats the second qubit of an entangled pair next to the first; true when the pair is whole. */
    private boolean joinPair(int id, int qubit) {
        for (int k = 0; k < MAX_FLYERS; k++) {
            if (seatedFor[k] == id) {
                seatedFor[k] = 0;
                crystal.entangle(seatedQubit[k], qubit);
                return true;
            }
        }
        for (int k = 0; k < MAX_FLYERS; k++) {
            if (seatedFor[k] == 0) {
                seatedFor[k] = id;
                seatedQubit[k] = qubit;
                break;
            }
        }
        return false;
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
        if (dropLeft && crystal == null) {
            // the one drop a collapse leaves; it breathes slowly while it waits, then melts away
            float left = Math.max(0f, Math.min(1f, (DROP_WAIT - dropAge) / DROP_FADE));
            float r = DROP * (1.4f + 0.12f * (float) Math.sin(time * 2.4f)) * (0.3f + 0.7f * left) * (1f + 3f * dropGrow);
            if (swallowAge >= 0f) {
                // swelling over the whole glass
                float k = swallowAge / SWALLOW_TIME;
                r *= (float) Math.exp(2.4f * k * k);
            }
            // a spun drop flattens a little, so its turn shows
            float flat = 1f + 0.22f * dropGrow;
            out[0] = dropX; out[1] = dropY / aspect; out[2] = r; out[3] = left;
            out[4] = 200f + filmShift();
            out[5] = (float) Math.cos(dropSpin); out[6] = (float) Math.sin(dropSpin); out[7] = flat;
            n = 1;
        }
        for (int i = 0; i < MAX_PAIRS; i++) {
            if (!pon[i]) continue;
            float r = pairSize(i), g = gap(i);
            for (int s = -1; s <= 1; s += 2) {
                int o = n * 8;
                out[o] = px[i] + pax[i] * g * s;
                out[o + 1] = (py[i] + pay[i] * g * s) / aspect;
                out[o + 2] = r;
                out[o + 3] = 1f;
                out[o + 4] = pfilm[i] + filmShift();
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
            out[o + 4] = ffilm[j] + filmShift();
            out[o + 5] = fvx[j] / sp;
            out[o + 6] = fvy[j] / sp;
            out[o + 7] = stretch;
            n++;
        }
        // glints last: small, trembling, coming and going
        glintsFrom = n;
        for (int g = 0; g < MAX_GLINTS; g++) {
            if (!gon[g]) continue;
            float t = gage[g] / GLINT_LIFE;
            float life = Math.min(1f, t * 6f) * Math.min(1f, (1f - t) * 4f);
            float shake = 0.75f + 0.25f * (float) Math.sin(time * 23f + g * 5.1f);
            float turn = time * 7f + g * 2.3f + 0.6f * (float) Math.sin(time * 31f + g);
            int o = n * 8;
            out[o] = gx[g] + 0.004f * (float) Math.sin(time * 41f + g);
            out[o + 1] = gy[g] / aspect;
            out[o + 2] = 0.03f * (0.8f + 0.3f * shake);
            out[o + 3] = life * shake;
            out[o + 4] = 0f;
            out[o + 5] = (float) Math.cos(turn);
            out[o + 6] = (float) Math.sin(turn);
            out[o + 7] = 2.6f;
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
        return MAX_PAIRS * 2 + MAX_FLYERS + MAX_GLINTS + 1;
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
        if (collapse >= 0f) {
            float k = collapseLevel();
            return Math.min(0.06f, 0.0011f * mass() + 0.05f * k * k);
        }
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
        // in a collapse the cluster shrinks to a point about its middle
        float k = collapseLevel(), shrink = 1f - k * k * (3f - 2f * k) * 0.985f;
        Mat.spinScaleAround(model, spin, shrink, mid[0], mid[1], mid[2]);
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
