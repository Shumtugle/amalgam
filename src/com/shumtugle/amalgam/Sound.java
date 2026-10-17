package com.shumtugle.amalgam;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;

/**
 * Everything audible is synthesised here: bells struck under water, and a low string
 * that thickens and drops as the crystal gains mass. No sound files.
 *
 * Every partial is a rotating complex number, so the inner loop has no trigonometry:
 * one complex multiply and one decay multiply per partial per sample.
 */
final class Sound implements Runnable {
    static final int RATE = 44100;
    private static final int BLOCK = 256;
    private static final int VOICES = 10;

    // A church bell's profile: hum, prime, tierce, quint, nominal, an upper partial.
    private static final float[] RATIO = {0.5f, 1f, 1.19f, 1.5f, 2f, 2.74f};
    private static final float[] LEVEL = {0.55f, 1f, 0.45f, 0.3f, 0.3f, 0.1f};
    private static final float[] DECAY = {7f, 4.5f, 3.2f, 2.6f, 1.9f, 1.0f};
    /** Each partial sounds twice, the copy slightly sharp, so the bell shimmers as it dies. */
    private static final int PARTIALS = RATIO.length * 2;
    private static final int HARMONICS = 6;

    private final Object lock = new Object();
    private final float[][] re = new float[VOICES][PARTIALS], im = new float[VOICES][PARTIALS];
    private final float[][] cr = new float[VOICES][PARTIALS], ci = new float[VOICES][PARTIALS];
    private final float[][] amp = new float[VOICES][PARTIALS], mul = new float[VOICES][PARTIALS];
    private final float[] attack = new float[VOICES];
    private final boolean[] on = new boolean[VOICES];
    private int nextVoice;

    // the string
    private final float[] dre = new float[HARMONICS * 2], dim = new float[HARMONICS * 2];
    private final float[] dcr = new float[HARMONICS * 2], dci = new float[HARMONICS * 2];
    private float droneFreq, droneLevel;
    private volatile float droneTarget = 98f, droneTargetLevel, droneThick = 2f, breath, disorder;

    // strikes waiting their turn, so a phrase can be played back note by note
    private static final int LATER = 16;
    private final float[] laterFreq = new float[LATER], laterLevel = new float[LATER], laterRing = new float[LATER];
    private final int[] laterWait = new int[LATER];

    // two one-pole low-passes: the water
    private float lp1, lp2;
    private static final float WATER = 1f - (float) Math.exp(-2.0 * Math.PI * 950.0 / RATE);

    private volatile boolean running;
    private Thread thread;

    Sound() {
        for (int h = 0; h < HARMONICS * 2; h++) dre[h] = 1f;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "sound");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        thread = null;
    }

    /** Strikes a bell. Level is about 1 for a full strike; ring stretches the decay. */
    void strike(float freq, float level, float ring) {
        synchronized (lock) {
            strikeNow(freq, level, ring);
        }
    }

    private void strikeNow(float freq, float level, float ring) {
        int v = nextVoice;
        nextVoice = (nextVoice + 1) % VOICES;
        for (int p = 0; p < PARTIALS; p++) {
            int k = p % RATIO.length;
            boolean twin = p >= RATIO.length;
            float f = freq * RATIO[k] * (twin ? 1.0027f : 1f);
            double th = 2.0 * Math.PI * f / RATE;
            re[v][p] = 1f;
            im[v][p] = 0f;
            cr[v][p] = (float) Math.cos(th);
            ci[v][p] = (float) Math.sin(th);
            amp[v][p] = f > 6000f ? 0f : LEVEL[k] * level * (twin ? 0.45f : 1f);
            mul[v][p] = (float) Math.exp(-1.0 / (DECAY[k] * ring * RATE));
        }
        attack[v] = 0f;
        on[v] = true;
    }

    /** Strikes a bell after a delay in seconds. */
    void strikeLater(float freq, float level, float ring, float delay) {
        synchronized (lock) {
            for (int i = 0; i < LATER; i++) {
                if (laterWait[i] > 0) continue;
                laterFreq[i] = freq;
                laterLevel[i] = level;
                laterRing[i] = ring;
                laterWait[i] = Math.max(1, (int) (delay * RATE));
                return;
            }
        }
    }

    /**
     * The state of the game, heard. The string drops as resonance gathers and gains harmonics
     * as the crystal gains mass; it is silent until something has been planted.
     */
    void setCrystal(boolean planted, float mass, float resonance, float pulse, float entropy) {
        disorder = entropy;
        droneTarget = 98f / (1f + 0.25f * (float) Math.log(1f + resonance * 0.5f));
        droneThick = Math.min(HARMONICS, 2f + mass * 0.4f);
        droneTargetLevel = planted ? 0.045f : 0f;
        breath = pulse;
    }

    @Override
    public void run() {
        AudioTrack t;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, BLOCK * 2 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM);
            if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            t = b.build();
            t.play();
        } catch (RuntimeException e) {
            running = false;
            return;
        }
        short[] out = new short[BLOCK];
        while (running) {
            synchronized (lock) {
                render(out);
            }
            t.write(out, 0, BLOCK);
        }
        t.stop();
        t.release();
    }

    private void render(short[] out) {
        for (int i = 0; i < LATER; i++) {
            if (laterWait[i] <= 0) continue;
            laterWait[i] -= BLOCK;
            if (laterWait[i] <= 0) strikeNow(laterFreq[i], laterLevel[i], laterRing[i]);
        }
        // the string glides to its new pitch and level once per block
        droneFreq += (droneTarget - droneFreq) * 0.02f;
        droneLevel += (droneTargetLevel * (0.7f + 0.6f * breath) - droneLevel) * 0.05f;
        float thick = droneThick;
        for (int h = 0; h < HARMONICS * 2; h++) {
            int n = h % HARMONICS + 1;
            // disorder pulls the twin string out of tune, and the two beat against each other
            float f = droneFreq * n * (h >= HARMONICS ? 1.004f + 0.022f * disorder : 1f);
            double th = 2.0 * Math.PI * f / RATE;
            dcr[h] = (float) Math.cos(th);
            dci[h] = (float) Math.sin(th);
        }
        float rise = 1f / (0.012f * RATE);
        for (int i = 0; i < BLOCK; i++) {
            float s = 0f;
            for (int v = 0; v < VOICES; v++) {
                if (!on[v]) continue;
                float[] r = re[v], m = im[v], c = cr[v], q = ci[v], a = amp[v], d = mul[v];
                float sum = 0f;
                for (int p = 0; p < PARTIALS; p++) {
                    float nr = r[p] * c[p] - m[p] * q[p];
                    float ni = r[p] * q[p] + m[p] * c[p];
                    r[p] = nr;
                    m[p] = ni;
                    sum += ni * a[p];
                    a[p] *= d[p];
                }
                float at = attack[v];
                if (at < 1f) attack[v] = Math.min(1f, at + rise);
                s += sum * at;
            }
            float dr = 0f;
            for (int h = 0; h < HARMONICS * 2; h++) {
                int n = h % HARMONICS + 1;
                float w = n <= thick ? 1f / n : (n - thick < 1f ? (1f - (n - thick)) / n : 0f);
                float nr = dre[h] * dcr[h] - dim[h] * dci[h];
                float ni = dre[h] * dci[h] + dim[h] * dcr[h];
                dre[h] = nr;
                dim[h] = ni;
                dr += ni * w;
            }
            s = s * 0.3f + dr * droneLevel;
            lp1 += WATER * (s - lp1);
            lp2 += WATER * (lp1 - lp2);
            float y = lp2 * 1.6f;
            y = y / (1f + Math.abs(y));
            out[i] = (short) (y * 32000f);
        }
        // keep rotors on the unit circle and retire bells that have faded out
        for (int v = 0; v < VOICES; v++) {
            if (!on[v]) continue;
            float total = 0f;
            for (int p = 0; p < PARTIALS; p++) {
                float g = 1.5f - 0.5f * (re[v][p] * re[v][p] + im[v][p] * im[v][p]);
                re[v][p] *= g;
                im[v][p] *= g;
                total += amp[v][p];
            }
            if (total < 1e-4f) on[v] = false;
        }
        for (int h = 0; h < HARMONICS * 2; h++) {
            float g = 1.5f - 0.5f * (dre[h] * dre[h] + dim[h] * dim[h]);
            dre[h] *= g;
            dim[h] *= g;
        }
    }
}
