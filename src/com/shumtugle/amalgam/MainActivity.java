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
        stage = new Stage(this);
        stage.setRestore(Save.load(this));
        setContentView(stage);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideBars();
        stage.resumeClock();
        stage.onResume();
        sound.start();
    }

    @Override
    protected void onPause() {
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
        sound.setCrystal(w.planted(), w.mass(), w.resonance, w.pulse);
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
