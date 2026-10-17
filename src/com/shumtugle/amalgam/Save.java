package com.shumtugle.amalgam;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The whole game in a handful of numbers. The crystal is not stored shape by shape: its seed
 * grows the same hoppers in the same order, so the count of finished ones is enough.
 */
final class Save {
    private Save() {}

    static final class State {
        long seed;
        boolean planted;
        float u, v, grown, pool, resonance;
        int settled;
        /** Seconds since the game was saved. */
        float away;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("game", Context.MODE_PRIVATE);
    }

    static State load(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("seed")) return null;
        State s = new State();
        s.seed = p.getLong("seed", 0L);
        s.planted = p.getBoolean("planted", false);
        s.u = p.getFloat("u", 0.5f);
        s.v = p.getFloat("v", 0.5f);
        s.settled = p.getInt("settled", 0);
        s.grown = p.getFloat("grown", 0f);
        s.pool = p.getFloat("pool", 0f);
        s.resonance = p.getFloat("resonance", 0f);
        long saved = p.getLong("saved", 0L);
        s.away = saved > 0 ? Math.max(0L, System.currentTimeMillis() - saved) / 1000f : 0f;
        return s;
    }

    static void store(Context c, World w) {
        if (w == null) return;
        SharedPreferences.Editor e = prefs(c).edit();
        e.putLong("seed", w.seed);
        e.putBoolean("planted", w.planted());
        if (w.planted()) {
            Crystal k = w.crystal;
            e.putFloat("u", w.seedU);
            e.putFloat("v", w.seedV);
            e.putInt("settled", k.settledCount());
            e.putFloat("grown", k.active != null ? k.active.grown : 1f);
            e.putFloat("pool", w.pool);
            e.putFloat("resonance", w.resonance);
        }
        e.putLong("saved", System.currentTimeMillis());
        e.apply();
    }
}
