package com.shumtugle.amalgam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The surface: collects touches on the UI thread, then on the GL thread advances the world
 * at a fixed rate and draws the liquid and the crystal.
 */
final class Stage extends GLSurfaceView implements GLSurfaceView.Renderer {
    private static final String TAG = "Amalgam";
    /** Columns of the liquid grid; rows follow the screen shape. */
    private static final int COLS = 160;
    private static final float STEP = 1f / 60f;

    private final MainActivity act;
    private volatile World world;
    /** A saved game to bring back when the surface first appears, or null for a fresh start. */
    private Save.State restore;

    /** Touches waiting for the GL thread: kind, u, v triples. */
    private final float[] pending = new float[3 * 64];
    private int pendingCount;
    private final float[] lastX = new float[10], lastY = new float[10];

    private int glass, crystal, drop, vista, eyeProg, wordProg, wordTex, starProg, cutProg, slabProg;
    /** The picture of the world taken at a cut, the top of the slab that turns. */
    private int snapFbo, snapTex, snapDepth;
    private boolean snapped;
    private int slabBuf, capBuf;
    private final float[] cap = new float[6 * Crystal.FLOATS];
    private FloatBuffer capOut;
    private final float[] slab = new float[64 * 4];
    private FloatBuffer slabOut;
    private final float[] cutPlanes = new float[Crystal.MAX_CUTS * 4];
    private final float[] strata = new float[21];
    private final float[] starAt = new float[2];
    private int fieldTex, triBuf, settledBuf, activeBuf, quadBuf, threadBuf;
    private int threadVerts;
    private final float[] drops = new float[World.maxDrops() * 8];
    private int settledVerts, settledVersion = -1;
    private float[] meshScratch;
    private FloatBuffer meshOut;
    private ByteBuffer fieldOut;
    private int width, height;

    private long lastNanos;
    private float accum;
    private final int[] events = new int[16];
    private final float[] mvp = new float[16], model = new float[16], eye = new float[3], shift = new float[2];

    // frame timing for the log, so a phone test tells how heavy the shaders are
    private int frames;
    private long frameNanos, statStart;

    Stage(MainActivity act) {
        super(act);
        this.act = act;
        setEGLContextClientVersion(3);
        // stencil for the faces cuts leave in the crystal
        setEGLConfigChooser(8, 8, 8, 8, 16, 8);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return true;
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            int id = e.getPointerId(i);
            queue(0, e.getX(i) / w, 1f - e.getY(i) / h);
            if (id < lastX.length) { lastX[id] = e.getX(i); lastY[id] = e.getY(i); }
        } else if (a == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                int id = e.getPointerId(i);
                if (id >= lastX.length) continue;
                float dx = e.getX(i) - lastX[id], dy = e.getY(i) - lastY[id];
                // a ripple every few millimetres of travel, not every event
                if (dx * dx + dy * dy > (w * 0.02f) * (w * 0.02f)) {
                    queue(1, e.getX(i) / w, 1f - e.getY(i) / h);
                    lastX[id] = e.getX(i);
                    lastY[id] = e.getY(i);
                }
            }
        }
        return true;
    }

    /** Hands over a saved game; it is applied when the surface first appears. */
    void setRestore(Save.State s) {
        restore = s;
    }

    /** The world, for saving. Read it only while the render thread is paused. */
    World world() {
        return world;
    }

    /** The system back, handed to the world on the render thread. */
    void back() {
        queue(2, 0f, 0f);
    }

    /** The word of the watcher's ending, drawn once with the system's serif face. */
    private int makeWord() {
        Bitmap b = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        p.setTextSize(300f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics m = p.getFontMetrics();
        c.drawText("try", 512f, 256f - (m.ascent + m.descent) / 2f, p);
        int[] ids = new int[1];
        GLES30.glGenTextures(1, ids, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0]);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        b.recycle();
        return ids[0];
    }

    /** Called when the activity comes back, so the pause does not count as one long frame. */
    void resumeClock() {
        lastNanos = 0;
    }

    private void queue(int kind, float u, float v) {
        synchronized (pending) {
            if (pendingCount * 3 + 3 > pending.length) return;
            pending[pendingCount * 3] = kind;
            pending[pendingCount * 3 + 1] = u;
            pending[pendingCount * 3 + 2] = v;
            pendingCount++;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        glass = program("glass.vert", "glass.frag");
        crystal = program("crystal.vert", "crystal.frag");
        drop = program("drop.vert", "drop.frag");
        vista = program("glass.vert", "vista.frag");
        eyeProg = program("drop.vert", "eye.frag");
        wordProg = program("word.vert", "word.frag");
        starProg = program("drop.vert", "star.frag");
        cutProg = program("glass.vert", "cut.frag");
        slabProg = program("slab.vert", "slab.frag");
        wordTex = makeWord();
        int[] ids = new int[7];
        GLES30.glGenBuffers(7, ids, 0);
        triBuf = ids[0];
        settledBuf = ids[1];
        activeBuf = ids[2];
        quadBuf = ids[3];
        threadBuf = ids[4];
        slabBuf = ids[5];
        capBuf = ids[6];
        capOut = ByteBuffer.allocateDirect(cap.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        slabOut = ByteBuffer.allocateDirect(slab.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        // a fresh context has none of the old objects
        snapFbo = 0;
        snapped = false;
        FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, quad, GLES30.GL_STATIC_DRAW);
        FloatBuffer tri = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        tri.put(new float[] {-1, -1, 3, -1, -1, 3}).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triBuf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 6 * 4, tri, GLES30.GL_STATIC_DRAW);
        GLES30.glGenTextures(1, ids, 0);
        fieldTex = ids[0];
        settledVersion = -1;
        fieldOut = null;
        lastNanos = 0;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = w;
        height = h;
        GLES30.glViewport(0, 0, w, h);
        float aspect = (float) h / w;
        if (world == null || Math.abs(world.aspect - aspect) > 0.01f)
        {
            Save.State r = restore;
            restore = null;
            world = new World(COLS, aspect, r != null ? r.seed : SystemClock.elapsedRealtime());
            if (r != null) {
                world.played = r.played;
                world.watched = r.watched;
            }
            if (r != null && r.planted) world.restore(r.generation, r.epoch, r.u, r.v, r.settled, r.grown, r.hues, r.partners, r.entropy, r.pool, r.resonance, r.away, r.layer, r.heard, r.lostAt, r.cuts, r.cutAt);
            else if (r != null) world.between(r.generation, r.epoch, r.resonance, r.layer);
        }
        Liquid lq = world.liquid;
        fieldOut = ByteBuffer.allocateDirect(lq.texels.length).order(ByteOrder.nativeOrder());
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, lq.cols, lq.rows, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        if (meshScratch == null) {
            int floats = Crystal.MAX_HOPPERS * Crystal.HOPPER_VERTS * Crystal.FLOATS;
            meshScratch = new float[floats];
            meshOut = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        settledVersion = -1;
        makeSnap(w, h);
    }

    /** A texture the size of the screen with a depth buffer, to take the picture of the world in. */
    private void makeSnap(int w, int h) {
        int[] ids = new int[1];
        if (snapFbo != 0) {
            ids[0] = snapFbo;
            GLES30.glDeleteFramebuffers(1, ids, 0);
            ids[0] = snapTex;
            GLES30.glDeleteTextures(1, ids, 0);
            ids[0] = snapDepth;
            GLES30.glDeleteRenderbuffers(1, ids, 0);
        }
        GLES30.glGenTextures(1, ids, 0);
        snapTex = ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, snapTex);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glGenRenderbuffers(1, ids, 0);
        snapDepth = ids[0];
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, snapDepth);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH24_STENCIL8, w, h);
        GLES30.glGenFramebuffers(1, ids, 0);
        snapFbo = ids[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, snapTex, 0);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_STENCIL_ATTACHMENT,
                GLES30.GL_RENDERBUFFER, snapDepth);
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE)
            Log.w(TAG, "the picture for the cut cannot be taken; the cut will show without it");
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /** Draws the world into the picture for the cut, as it stands at the stroke. */
    private void takeSnap() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, snapFbo);
        GLES30.glViewport(0, 0, width, height);
        GLES30.glClearColor(0f, 0f, 0f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT | GLES30.GL_STENCIL_BUFFER_BIT);
        drawScene();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, width, height);
    }

    private void drawScene() {
        drawGlass();
        drawStars();
        drawVision();
        drawDrops();
        drawCrystal();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? STEP : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;

        synchronized (pending) {
            for (int i = 0; i < pendingCount; i++) {
                float u = pending[i * 3 + 1], v = pending[i * 3 + 2];
                if (pending[i * 3] == 0) world.touch(u, v);
                else if (pending[i * 3] == 1) world.drag(u, v);
                else if (world.back()) act.quit();
            }
            pendingCount = 0;
        }

        // a cut has just been made: take the picture of the world whole before it falls apart
        if (world.cutAge < 0f) {
            snapped = false;
        } else if (!snapped) {
            takeSnap();
            snapped = true;
            world.pictured = true;
        }

        // the world and the liquid run at a fixed rate whatever the display does
        // the watcher plays in quick time: more steps of the same size each frame
        accum += dt * world.speed();
        int steps = 0, most = world.speed() > 1f ? 8 : 4;
        while (accum >= STEP && steps < most) {
            world.update(STEP);
            world.liquid.step();
            accum -= STEP;
            steps++;
        }
        if (steps == most) accum = 0;

        int n = world.takeEvents(events);
        for (int i = 0; i < n; i++) act.onWorldEvent(events[i], world);
        act.onWorldFrame(world);

        float age = world.cutAge;
        if (age >= 0f && age < World.CUT_WASH) {
            // the world is a slab turning over on the cut; nothing live shows
            GLES30.glClearColor(0f, 0f, 0f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
            drawSlab(0f);
            drawCut(0f, 1f);
        } else {
            drawScene();
            drawWord();
            if (age >= 0f) {
                // the new surface spreads from the cut over what is left of the slab, and the
                // crystal rises through it
                float reach = World.washReach(age);
                float fade = 1f - clamp01((age - World.CUT_RISE) / (World.CUT_TIME - World.CUT_RISE));
                if (reach < 3f) drawSlab(reach);
                drawCut(reach, fade);
            }
        }
        drawEye();

        frames++;
        frameNanos += System.nanoTime() - now;
        if (statStart == 0) statStart = now;
        if (now - statStart > 5_000_000_000L) {
            Log.i(TAG, String.format("%dx%d %.1f fps, %.2f ms cpu a frame", width, height,
                    frames / ((now - statStart) / 1e9f), frameNanos / 1e6f / frames));
            frames = 0;
            frameNanos = 0;
            statStart = now;
        }
    }

    private void drawGlass() {
        Liquid lq = world.liquid;
        lq.encode();
        fieldOut.position(0);
        fieldOut.put(lq.texels).position(0);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(glass);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, lq.cols, lq.rows,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, fieldOut);
        GLES30.glUniform1i(loc(glass, "uField"), 0);
        float glowU = 0.5f, glowV = 0.5f, glow = 0f;
        if (world.planted()) {
            glowU = world.crystalU();
            glowV = world.crystalV();
            glow = 0.4f + 0.6f * world.pulse + world.flash + 2f * world.collapseLevel();
        }
        GLES30.glUniform4f(loc(glass, "uGlow"), glowU, glowV, glow, world.aspect);
        GLES30.glUniform1f(loc(glass, "uFilm"), world.filmShift());
        GLES30.glUniform1f(loc(glass, "uLens"), world.lens());
        float[] tint = world.tint();
        GLES30.glUniform3f(loc(glass, "uLayer"), tint[0], tint[1], tint[2]);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    /** A vision of deep space added over the liquid, bent by its ripples. */
    private void drawVision() {
        float a = world.visionAlpha();
        if (world.vision < 0 || a <= 0f) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GLES30.glUseProgram(vista);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex);
        GLES30.glUniform1i(loc(vista, "uField"), 0);
        GLES30.glUniform1i(loc(vista, "uScene"), world.vision);
        GLES30.glUniform1f(loc(vista, "uAlpha"), a);
        GLES30.glUniform1f(loc(vista, "uTime"), world.visionAge);
        GLES30.glUniform1f(loc(vista, "uAspect"), world.aspect);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    /** One star for every finished cycle, seen only through still water. */
    private void drawStars() {
        int played = Math.min(world.played, World.MAX_STARS), watched = Math.min(world.watched, World.MAX_STARS);
        if (played + watched == 0) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GLES30.glUseProgram(starProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex);
        GLES30.glUniform1i(loc(starProg, "uField"), 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glUniform1f(loc(starProg, "uAspect"), world.aspect);
        GLES30.glUniform2f(loc(starProg, "uDir"), 1f, 0f);
        GLES30.glUniform1f(loc(starProg, "uStretch"), 1f);
        GLES30.glUniform1f(loc(starProg, "uTime"), world.time % 1000f);
        int center = loc(starProg, "uCenter"), radius = loc(starProg, "uRadius");
        int tint = loc(starProg, "uTint"), seed = loc(starProg, "uSeed");
        for (int pass = 0; pass < 2; pass++) {
            boolean byWatcher = pass == 1;
            int n = byWatcher ? watched : played;
            GLES30.glUniform1f(radius, byWatcher ? 0.009f : 0.014f);
            if (byWatcher) GLES30.glUniform3f(tint, 0.42f, 0.55f, 0.85f);
            else GLES30.glUniform3f(tint, 1f, 0.9f, 0.72f);
            for (int k = 0; k < n; k++) {
                world.star(k, byWatcher, starAt);
                GLES30.glUniform2f(center, starAt[0], starAt[1]);
                GLES30.glUniform1f(seed, (k * 0.618f + (byWatcher ? 0.31f : 0f)) % 1f);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            }
        }
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    /** The colours of the strata, the next surface first. */
    private void strata() {
        // until the cut takes effect the next surface is one up from the present one
        int first = world.cutDone ? world.layer : world.layer + 1;
        for (int k = 0; k < World.LAYERS; k++) {
            float[] c = World.TINT[(first + k) % World.LAYERS];
            strata[k * 3] = c[0]; strata[k * 3 + 1] = c[1]; strata[k * 3 + 2] = c[2];
        }
    }

    /** Over the slab: the line of the stroke, then the next colour spreading from the cut. */
    private void drawCut(float reach, float fade) {
        if (fade <= 0f && world.cutAge > 1f) return;
        strata();
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(cutProg);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, triBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glUniform2f(loc(cutProg, "uPoint"), world.cutX, world.cutY / world.aspect);
        GLES30.glUniform2f(loc(cutProg, "uNormal"), world.cutNX, world.cutNY);
        GLES30.glUniform1f(loc(cutProg, "uAspect"), world.aspect);
        GLES30.glUniform1f(loc(cutProg, "uAge"), world.cutAge);
        GLES30.glUniform1f(loc(cutProg, "uReach"), reach);
        GLES30.glUniform1f(loc(cutProg, "uFade"), fade);
        GLES30.glUniform3f(loc(cutProg, "uNext"), strata[0], strata[1], strata[2]);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    /**
     * The world as a slab during a cut, its top the picture taken at the stroke. The piece that
     * goes tips over the cut and falls into the black; the piece that stays turns on the cut
     * until its cut face, the strata, looks up.
     */
    private void drawSlab(float reach) {
        float a = world.aspect, age = world.cutAge;
        float cx = world.cutX, cy = world.cutY, nx = world.cutNX, ny = world.cutNY;
        float tx = -ny, ty = nx;
        // how deep the slab is: as far as the cut is from the farthest corner of the piece
        // that goes, so the turned face covers the glass where that piece was
        float thick = 0.3f, s0 = 1e9f, s1 = -1e9f;
        for (int c = 0; c < 4; c++) {
            float px = (c & 1) == 0 ? 0f : 1f, py = (c & 2) == 0 ? 0f : a;
            thick = Math.max(thick, (px - cx) * nx + (py - cy) * ny);
            float s = (px - cx) * tx + (py - cy) * ty;
            s0 = Math.min(s0, s);
            s1 = Math.max(s1, s);
        }
        strata();
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LESS);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glUseProgram(slabProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, snapTex);
        GLES30.glUniform1i(loc(slabProg, "uSnap"), 0);
        GLES30.glUniform3fv(loc(slabProg, "uStrata"), World.LAYERS, strata, 0);
        GLES30.glUniform1f(loc(slabProg, "uThick"), thick);
        GLES30.glUniform1f(loc(slabProg, "uAlpha"), 1f);
        GLES30.glUniform1f(loc(slabProg, "uReach"), reach);
        GLES30.glUniform2f(loc(slabProg, "uRes"), width, height);
        GLES30.glUniform2f(loc(slabProg, "uPoint"), cx, cy);
        GLES30.glUniform2f(loc(slabProg, "uNormal"), nx, ny);
        GLES30.glUniform1f(loc(slabProg, "uAspect"), a);
        GLES30.glUniform1f(loc(slabProg, "uEyeHeight"), 1.8f);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, slabBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 4, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);

        // the piece that goes: tipping faster and faster, then dropping out of sight
        float f = clamp01((age - World.CUT_FALL) / 1.5f);
        if (f < 1f) {
            int n = piece(1f, cx, cy, nx, ny, tx, ty, s0, s1, thick);
            uploadSlab(n);
            GLES30.glUniform1f(loc(slabProg, "uSide"), -1f);
            GLES30.glUniform1f(loc(slabProg, "uAngle"), -1.35f * f * f);
            GLES30.glUniform1f(loc(slabProg, "uDrop"), 2.5f * f * f * f);
            GLES30.glUniform1f(loc(slabProg, "uDim"), (1f - f) * (1f - f));
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, n);
        }
        // the piece that stays: turning on the cut until the strata face the eye
        float k = clamp01((age - World.CUT_TURN) / (World.CUT_WASH - World.CUT_TURN));
        float e = k * k * k * (k * (k * 6f - 15f) + 10f);
        int n = piece(-1f, cx, cy, nx, ny, tx, ty, s0, s1, thick);
        uploadSlab(n);
        GLES30.glUniform1f(loc(slabProg, "uSide"), 1f);
        GLES30.glUniform1f(loc(slabProg, "uAngle"), 1.5708f * e);
        GLES30.glUniform1f(loc(slabProg, "uDrop"), 0f);
        GLES30.glUniform1f(loc(slabProg, "uDim"), 1f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, n);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
    }

    private static float clamp01(float x) {
        return x < 0f ? 0f : x > 1f ? 1f : x;
    }

    /**
     * Writes one piece of the slab as triangles into slab[]: its top, the screen cut down to
     * the side of the line where side times the distance across it is positive, and its cut face.
     * Returns the vertex count.
     */
    private int piece(float side, float cx, float cy, float nx, float ny, float tx, float ty,
                      float s0, float s1, float thick) {
        float a = world.aspect;
        float[] in = {0f, 0f, 1f, 0f, 1f, a, 0f, a};
        float[] poly = new float[16];
        int m = 0;
        for (int i = 0; i < 4; i++) {
            float ax = in[i * 2], ay = in[i * 2 + 1];
            float bx = in[(i + 1) % 4 * 2], by = in[(i + 1) % 4 * 2 + 1];
            float da = side * ((ax - cx) * nx + (ay - cy) * ny), db = side * ((bx - cx) * nx + (by - cy) * ny);
            if (da >= 0f) { poly[m * 2] = ax; poly[m * 2 + 1] = ay; m++; }
            if ((da >= 0f) != (db >= 0f)) {
                float t = da / (da - db);
                poly[m * 2] = ax + (bx - ax) * t;
                poly[m * 2 + 1] = ay + (by - ay) * t;
                m++;
            }
        }
        int v = 0;
        for (int i = 1; i + 1 < m; i++) {
            v = slabVert(v, poly[0], poly[1], 0f, 0f);
            v = slabVert(v, poly[i * 2], poly[i * 2 + 1], 0f, 0f);
            v = slabVert(v, poly[i * 2 + 2], poly[i * 2 + 3], 0f, 0f);
        }
        float ax = cx + tx * s0, ay = cy + ty * s0, bx = cx + tx * s1, by = cy + ty * s1;
        v = slabVert(v, ax, ay, 0f, 1f);
        v = slabVert(v, bx, by, 0f, 1f);
        v = slabVert(v, bx, by, -thick, 1f);
        v = slabVert(v, ax, ay, 0f, 1f);
        v = slabVert(v, bx, by, -thick, 1f);
        v = slabVert(v, ax, ay, -thick, 1f);
        return v;
    }

    private int slabVert(int v, float x, float y, float z, float face) {
        int o = v * 4;
        slab[o] = x; slab[o + 1] = y; slab[o + 2] = z; slab[o + 3] = face;
        return v + 1;
    }

    private void uploadSlab(int verts) {
        slabOut.position(0);
        slabOut.put(slab, 0, verts * 4).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, slabBuf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts * 16, slabOut, GLES30.GL_STREAM_DRAW);
    }

    /** The eye in the corner, over everything. */
    private void drawEye() {
        if (world.eye <= 0.01f) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(eyeProg);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glUniform2f(loc(eyeProg, "uCenter"), World.EYE_U, world.eyeV());
        GLES30.glUniform1f(loc(eyeProg, "uRadius"), World.EYE_R / 1.15f);
        GLES30.glUniform1f(loc(eyeProg, "uAspect"), world.aspect);
        GLES30.glUniform2f(loc(eyeProg, "uDir"), 1f, 0f);
        GLES30.glUniform1f(loc(eyeProg, "uStretch"), 1f);
        GLES30.glUniform1f(loc(eyeProg, "uAlpha"), world.eye);
        GLES30.glUniform1f(loc(eyeProg, "uTime"), world.time % 1000f);
        GLES30.glUniform1f(loc(eyeProg, "uOpen"), world.watcher != null ? 1f : 0f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    /** The word the watcher's game ends on, rushing out of the point where the crystal fell. */
    private void drawWord() {
        float t = world.wordAge;
        if (t < 0f) return;
        float a = Math.min(1f, t / 1f) * (1f - Math.max(0f, Math.min(1f, (t - 3.2f) / 1.4f)));
        if (a <= 0f) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
        GLES30.glUseProgram(wordProg);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wordTex);
        GLES30.glUniform1i(loc(wordProg, "uText"), 0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        // drifting towards the middle as it grows, so the dive goes through the centre
        float k = Math.min(1f, t / 3f);
        GLES30.glUniform2f(loc(wordProg, "uCenter"), world.wordU + (0.5f - world.wordU) * k, world.wordV + (0.5f - world.wordV) * k);
        GLES30.glUniform1f(loc(wordProg, "uSize"), 0.04f * (float) Math.exp(1.25f * t));
        GLES30.glUniform1f(loc(wordProg, "uAspect"), world.aspect);
        GLES30.glUniform1f(loc(wordProg, "uAlpha"), a);
        GLES30.glUniform1f(loc(wordProg, "uTime"), t);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void drawDrops() {
        int n = world.drops(drops);
        if (n == 0) return;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(drop);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glDisableVertexAttribArray(2);
        GLES30.glUniform1f(loc(drop, "uAspect"), world.aspect);
        float[] tint = world.tint();
        GLES30.glUniform3f(loc(drop, "uLayer"), tint[0], tint[1], tint[2]);
        int center = loc(drop, "uCenter"), radius = loc(drop, "uRadius"), alpha = loc(drop, "uAlpha");
        int film = loc(drop, "uFilm"), dir = loc(drop, "uDir"), stretch = loc(drop, "uStretch");
        for (int i = 0; i < n; i++) {
            int o = i * 8;
            if (drops[o + 2] <= 0f || drops[o + 3] <= 0f) continue;
            GLES30.glUniform2f(center, drops[o], drops[o + 1]);
            GLES30.glUniform1f(radius, drops[o + 2]);
            GLES30.glUniform1f(alpha, drops[o + 3]);
            GLES30.glUniform1f(film, drops[o + 4]);
            GLES30.glUniform2f(dir, drops[o + 5], drops[o + 6]);
            GLES30.glUniform1f(stretch, drops[o + 7]);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void drawCrystal() {
        Crystal c = world.crystal;
        // under the new surface after a cut, before it rises, the crystal is out of sight
        if (c == null || world.cutSink() > 0.98f) return;
        if (c.settledVersion != settledVersion) {
            settledVersion = c.settledVersion;
            settledVerts = c.buildSettled(meshScratch);
            upload(settledBuf, settledVerts, GLES30.GL_STATIC_DRAW);

        }
        int activeVerts = c.buildMoving(meshScratch);
        if (activeVerts > 0) upload(activeBuf, activeVerts, GLES30.GL_DYNAMIC_DRAW);
        // strings vibrate, so they are rebuilt every frame
        threadVerts = world.stringsShown() ? c.buildThreads(meshScratch, world.time, world.strings) : 0;
        if (threadVerts > 0) upload(threadBuf, threadVerts, GLES30.GL_DYNAMIC_DRAW);

        world.camera(mvp, model, eye, shift);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LESS);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glUseProgram(crystal);
        GLES30.glUniformMatrix4fv(loc(crystal, "uMvp"), 1, false, mvp, 0);
        GLES30.glUniformMatrix4fv(loc(crystal, "uModel"), 1, false, model, 0);
        GLES30.glUniform2f(loc(crystal, "uShift"), shift[0], shift[1]);
        GLES30.glUniform3f(loc(crystal, "uEye"), eye[0], eye[1], eye[2]);
        GLES30.glUniform1f(loc(crystal, "uPulse"), world.pulse);
        GLES30.glUniform1f(loc(crystal, "uFlash"), world.flash);
        GLES30.glUniform1f(loc(crystal, "uFilm"), world.filmShift());
        GLES30.glUniform1f(loc(crystal, "uEntropy"), world.entropy);
        GLES30.glUniform1f(loc(crystal, "uTime"), world.time % 1000f);
        int cuts = Math.min(Crystal.MAX_CUTS, c.cutCount);
        System.arraycopy(c.cuts, 0, cutPlanes, 0, cuts * 4);
        GLES30.glUniform4fv(loc(crystal, "uCuts"), Crystal.MAX_CUTS, cutPlanes, 0);
        GLES30.glUniform1i(loc(crystal, "uCutCount"), cuts);
        GLES30.glUniform1f(loc(crystal, "uSink"), world.cutSink());
        GLES30.glUniform1f(loc(crystal, "uSinkDepth"), world.sinkDepth());
        GLES30.glUniform1i(loc(crystal, "uCap"), -1);
        drawMesh(settledBuf, settledVerts);
        drawMesh(activeBuf, activeVerts);
        for (int k = 0; k < cuts; k++) drawCap(c, k, activeVerts);
        if (threadVerts > 0) {
            // entanglement shows through the metal: no depth test, light added on top
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE);
            drawMesh(threadBuf, threadVerts);
            GLES30.glDisable(GLES30.GL_BLEND);
        }
    }

    /**
     * The face cut k left in the metal. Counting along each ray the inner faces of the cut
     * cluster against its outer faces tells where the ray meets the plane inside the metal;
     * a square in the plane is drawn only there.
     */
    private void drawCap(Crystal c, int k, int activeVerts) {
        GLES30.glEnable(GLES30.GL_STENCIL_TEST);
        GLES30.glClear(GLES30.GL_STENCIL_BUFFER_BIT);
        GLES30.glColorMask(false, false, false, false);
        GLES30.glDepthMask(false);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glStencilFunc(GLES30.GL_ALWAYS, 0, 0xFF);
        GLES30.glStencilOpSeparate(GLES30.GL_BACK, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_INCR_WRAP);
        GLES30.glStencilOpSeparate(GLES30.GL_FRONT, GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_DECR_WRAP);
        drawMesh(settledBuf, settledVerts);
        drawMesh(activeBuf, activeVerts);
        GLES30.glColorMask(true, true, true, true);
        GLES30.glDepthMask(true);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glStencilFunc(GLES30.GL_NOTEQUAL, 0, 0xFF);
        GLES30.glStencilOp(GLES30.GL_KEEP, GLES30.GL_KEEP, GLES30.GL_KEEP);
        int n = c.buildCap(k, cap);
        capOut.position(0);
        capOut.put(cap, 0, n * Crystal.FLOATS).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, capBuf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * Crystal.FLOATS * 4, capOut, GLES30.GL_STREAM_DRAW);
        GLES30.glUniform1i(loc(crystal, "uCap"), k);
        drawMesh(capBuf, n);
        GLES30.glUniform1i(loc(crystal, "uCap"), -1);
        GLES30.glDisable(GLES30.GL_STENCIL_TEST);
    }

    private void upload(int buf, int verts, int usage) {
        int floats = verts * Crystal.FLOATS;
        meshOut.position(0);
        meshOut.put(meshScratch, 0, floats).position(0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, Math.max(4, floats * 4), meshOut, usage);
    }

    private static void drawMesh(int buf, int verts) {
        if (verts <= 0) return;
        int stride = Crystal.FLOATS * 4;
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12);
        GLES30.glEnableVertexAttribArray(2);
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, verts);
    }

    private static int loc(int prog, String name) {
        return GLES30.glGetUniformLocation(prog, name);
    }

    private int program(String vert, String frag) {
        String common = asset("shaders/common.glsl");
        int v = shader(GLES30.GL_VERTEX_SHADER, asset("shaders/" + vert).replace("#include common", common));
        int f = shader(GLES30.GL_FRAGMENT_SHADER, asset("shaders/" + frag).replace("#include common", common));
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link " + vert + ": " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compile: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private String asset(String name) {
        try {
            InputStream in = getContext().getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
