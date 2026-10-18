package com.shumtugle.amalgam;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.Random;

public final class MainActivity extends Activity {
    private Stage stage;
    private Vibrator vibrator;
    private final Sound sound = new Sound();
    private final Random rnd = new Random();
    /** Heavy thud from the vibrator's own library when it has one, else a shaped waveform. */
    private boolean thud;

    // A minor pentatonic around D; resonant strikes ring the low D, stray touches pick from above.
    private static final float LOW = 73.42f;
    private static final float[] SCALE = {293.66f, 349.23f, 392.0f, 440.0f, 523.25f};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Crash.install(getApplicationContext());
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && Build.VERSION.SDK_INT >= 30)
            thud = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD);
        Log.i("Amalgam", "vibrator: amplitude " + (vibrator != null && vibrator.hasAmplitudeControl())
                + ", thud " + thud);
        String report = Crash.pending(this);
        if (report != null) {
            Crash.show(this, report, new Runnable() {
                @Override
                public void run() {
                    startGame();
                    stage.resumeClock();
                    stage.onResume();
                    sound.start();
                    hideBars();
                }
            });
            return;
        }
        startGame();
    }

    private void startGame() {
        stage = new Stage(this);
        stage.setRestore(Save.load(this));
        setContentView(stage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stage == null) return;
        hideBars();
        stage.resumeClock();
        stage.onResume();
        sound.start();
    }

    @Override
    protected void onPause() {
        if (stage == null) {
            super.onPause();
            return;
        }
        stage.onPause();
        // the render thread is paused now, so the world holds still while it is written down
        Save.store(this, stage.world());
        sound.stop();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideBars();
    }

    @Override
    public void onBackPressed() {
        // back opens the eye; the world decides when back means leaving
        if (stage == null) {
            super.onBackPressed();
            return;
        }
        stage.back();
    }

    /** Leaves the game; safe to call from the render thread. */
    void quit() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Called on the render thread for every event the world raised this frame. */
    void onWorldEvent(int event, World w) {
        switch (event) {
            case World.EV_PLANT:
                sound.strike(LOW, 1f, 1.4f);
                sound.strike(LOW * 2f, 0.35f, 1.2f);
                thump(true);
                break;
            case World.EV_OBSERVE_ON_BEAT:
                // seen on the breath: the deep bell, and the escaping drop sings above it
                sound.strike(LOW, 1f, 1.4f);
                sound.strike(SCALE[rnd.nextInt(SCALE.length)] * 2f, 0.3f, 1.1f);
                thump(true);
                break;
            case World.EV_OBSERVE:
                sound.strike(SCALE[rnd.nextInt(SCALE.length)], 0.42f, 0.9f);
                thump(false);
                break;
            case World.EV_MISS:
                // looking at nothing: a dull knock, no bell
                sound.strike(LOW * 3f, 0.07f, 0.25f);
                break;
            case World.EV_ABSORB:
                sound.strike(LOW * 1.5f, 0.28f, 0.8f);
                tick();
                break;
            case World.EV_QUBIT: {
                // each new qubit is the next note of a scale that climbs with the spectrum
                int k = w.crystal.qubits - 1;
                int step = k * SCALE.length * 2 / Crystal.MAX_HOPPERS;
                float f = SCALE[step % SCALE.length] * (step >= SCALE.length ? 2f : 1f);
                sound.strike(f, 0.4f, 1.6f);
                sound.strike(LOW * 1.5f, 0.2f, 0.8f);
                tick();
                break;
            }
            case World.EV_PATTERN: {
                // the world sings the pattern back, then settles on a plain open chord
                Listener l = w.listener;
                float at = 0.15f;
                for (int i = 0; i < l.patternLength; i++, at += 0.2f) {
                    int sym = l.pattern[i];
                    float f = SCALE[(sym >> 1) % SCALE.length] * ((sym & 1) == 1 ? 2f : 1f);
                    sound.strikeLater(f, 0.38f, 1.2f, at);
                }
                sound.strikeLater(LOW, 0.7f, 2f, at + 0.1f);
                sound.strikeLater(LOW * 1.5f, 0.4f, 2f, at + 0.1f);
                sound.strikeLater(LOW * 2f, 0.3f, 2f, at + 0.1f);
                thump(true);
                break;
            }
            case World.EV_SINK:
                // a heavy body going down into something thick
                sound.strike(LOW * 1.2f, 0.4f, 0.7f);
                sound.strikeLater(LOW * 0.9f, 0.25f, 0.9f, 0.18f);
                thump(true);
                break;
            case World.EV_SURFACE:
                sound.strike(SCALE[0], 0.18f, 1.2f);
                sound.strikeLater(SCALE[2], 0.16f, 1.2f, 0.25f);
                tick();
                break;
            case World.EV_VISTA:
                if (w.vision == World.WARP) {
                    // the jump: a rush of rising bells
                    for (int i = 0; i < 10; i++)
                        sound.strikeLater(SCALE[i % SCALE.length] * (i >= SCALE.length ? 2f : 1f), 0.22f, 0.7f, i * 0.05f);
                } else if (w.vision == World.SUPERNOVA) {
                    sound.strike(LOW, 1f, 2.5f);
                    sound.strike(LOW * 1.5f, 0.6f, 2.5f);
                    thump(true);
                } else {
                    // a slow wide chord as the deep opens
                    sound.strike(LOW, 0.35f, 3f);
                    sound.strikeLater(LOW * 1.5f, 0.28f, 3f, 0.4f);
                    sound.strikeLater(LOW * 2f, 0.24f, 3f, 0.8f);
                    sound.strikeLater(LOW * 3f, 0.16f, 3f, 1.2f);
                }
                break;
            case World.EV_RING:
                // the circle closes: a soft rising pair of notes
                sound.strike(SCALE[2] * 2f, 0.16f, 1.4f);
                sound.strikeLater(SCALE[4] * 2f, 0.14f, 1.6f, 0.12f);
                tick();
                break;
            case World.EV_BOUNCE: {
                // the circle rings like a bell: a small circle rings high
                float f = Math.max(260f, Math.min(1400f, 70f / Math.max(0.05f, w.bounceSize)));
                sound.strike(f, 0.13f, 0.5f);
                break;
            }
            case World.EV_ENTANGLE:
                // two qubits that share one fate: two notes struck as one
                sound.strike(SCALE[0] * 2f, 0.32f, 2.2f);
                sound.strike(SCALE[3] * 2f, 0.28f, 2.2f);
                sound.strike(LOW * 2f, 0.3f, 1.6f);
                thump(false);
                break;
            case World.EV_PLUCK:
                sound.pluck(w.pluckPitch, 0.5f);
                tick();
                break;
            case World.EV_COLLAPSE: {
                // the cluster falls in: the scale runs down, then the deepest bell
                float at = 0f;
                for (int i = 9; i >= 0; i--, at += 0.42f)
                    sound.strikeLater(SCALE[i % SCALE.length] * (i >= SCALE.length ? 2f : 1f), 0.3f, 1.4f, at);
                sound.strikeLater(LOW, 1f, 3f, World.COLLAPSE_TIME - 0.05f);
                thump(true);
                break;
            }
            case World.EV_SINGULARITY:
                sound.strike(LOW * 1.5f, 0.6f, 3f);
                sound.strike(LOW * 2f, 0.4f, 3f);
                thump(true);
                break;
            case World.EV_PEACE:
                // the last sound: one faint high note, and then nothing
                sound.strike(SCALE[4] * 2f, 0.08f, 2.5f);
                break;
            case World.EV_EYE:
                sound.strike(SCALE[3] * 2f, 0.1f, 1.2f);
                break;
            case World.EV_WATCH:
                // the eye takes over: a low note and a widening
                sound.strike(LOW * 2f, 0.4f, 2f);
                sound.strikeLater(LOW * 3f, 0.25f, 2f, 0.3f);
                thump(false);
                break;
            case World.EV_GLINT:
                // a glint caught: a small bright chime
                sound.strike(SCALE[3] * 4f, 0.09f, 0.7f);
                sound.strikeLater(SCALE[4] * 4f, 0.06f, 0.6f, 0.08f);
                break;
            case World.EV_SPIN:
                // each quarter turn of the drop a little higher
                sound.strike(SCALE[w.dropGrow < 0.5f ? 2 : 4] * (1f + w.dropGrow), 0.07f, 0.8f);
                break;
            case World.EV_SWALLOW:
                // the last sound there is: a long low note going out under the black
                sound.strike(LOW * 0.5f, 0.5f, World.SWALLOW_TIME);
                thump(true);
                break;
            case World.EV_RETURN:
                tick();
                break;
            case World.EV_CUT:
                // a long tearing glide down, and the weight of the piece falling away
                for (int i = 0; i < 6; i++)
                    sound.strikeLater(SCALE[(5 - i) % SCALE.length] * 2f, 0.18f, 0.9f, i * 0.07f);
                sound.strikeLater(LOW, 0.8f, 2.2f, 0.6f);
                thump(true);
                break;
            case World.EV_TURN:
                // the world turns over on the cut: a slow grinding of stone
                sound.strike(LOW * 0.75f, 0.45f, 2.6f);
                sound.strikeLater(LOW * 0.5f, 0.35f, 2.2f, 0.35f);
                thump(false);
                break;
            case World.EV_LAYER:
                // the new surface: an open fifth, higher on the surfaces that listen to strings
                float base = World.listensToStrings(w.layer) ? LOW * 2f : LOW * 1.5f;
                sound.strike(base, 0.35f, 2.4f);
                sound.strike(base * 1.5f, 0.25f, 2.4f);
                tick();
                break;
            case World.EV_BREATH:
                // the breath must be heard, or nobody can look on it
                sound.strike(LOW, 0.16f, 0.6f);
                break;
            case World.EV_SETTLED:
                sound.strike(SCALE[4] * 2f, 0.12f, 1.5f);
                break;
            default:
                break;
        }
    }

    /** Called on the render thread once a frame. */
    void onWorldFrame(World w) {
        sound.setCrystal(w.planted(), w.mass(), w.resonance, w.pulse, w.entropy);
    }

    /** The faintest touch: something fell past the horizon. */
    private void tick() {
        Vibrator v = vibrator;
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        else v.vibrate(VibrationEffect.createOneShot(6, 60));
    }

    /**
     * A vibration motor can only buzz, so weight comes from shape: a hard hit, a gap, then a low
     * tail, the way a heavy body lands and settles.
     */
    private void thump(boolean strong) {
        Vibrator v = vibrator;
        if (v == null || !v.hasVibrator()) return;
        if (thud && Build.VERSION.SDK_INT >= 30) {
            VibrationEffect.Composition c = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, strong ? 1f : 0.45f);
            if (strong && Build.VERSION.SDK_INT >= 31
                    && v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK))
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f, 40);
            v.vibrate(c.compose());
        } else if (v.hasAmplitudeControl()) {
            long[] t = strong ? new long[] {0, 18, 26, 60} : new long[] {0, 12, 30};
            int[] a = strong ? new int[] {0, 255, 0, 80} : new int[] {0, 160, 35};
            v.vibrate(VibrationEffect.createWaveform(t, a, -1));
        } else {
            v.vibrate(VibrationEffect.createOneShot(strong ? 30 : 14, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
