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
        float u, v, grown, pool, resonance, entropy;
        float[] hues = new float[0];
        int[] partners = new int[0];
        int settled, generation;
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
        s.generation = p.getInt("generation", 0);
        s.u = p.getFloat("u", 0.5f);
        s.v = p.getFloat("v", 0.5f);
        s.settled = p.getInt("settled", 0);
        s.entropy = p.getFloat("entropy", 0.1f);
        String ps = p.getString("partners", "");
        if (ps.length() > 0) {
            String[] parts = ps.split(",");
            s.partners = new int[parts.length];
            for (int i = 0; i < parts.length; i++) s.partners[i] = Integer.parseInt(parts[i]);
        }
        String hs = p.getString("hues", "");
        if (hs.length() > 0) {
            String[] parts = hs.split(",");
            s.hues = new float[parts.length];
            for (int i = 0; i < parts.length; i++) s.hues[i] = Float.parseFloat(parts[i]);
        } else if (p.contains("qubits")) {
            // saved before qubits had colours of their own: they arrived in spectrum order
            int n = p.getInt("qubits", 0);
            s.hues = new float[n];
            for (int i = 0; i < n; i++) s.hues[i] = Math.min(1f, i / (float) (Crystal.MAX_HOPPERS - 1));
        }
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
        // a collapse under way is saved as finished: the next start finds the drop waiting
        boolean falling = w.collapse >= 0f;
        e.putInt("generation", w.generation + (falling ? 1 : 0));
        e.putFloat("resonance", w.resonance);
        e.putBoolean("planted", w.planted() && !falling);
        if (w.planted() && !falling) {
            Crystal k = w.crystal;
            e.putFloat("u", w.seedU);
            e.putFloat("v", w.seedV);
            e.putInt("settled", k.settledCount());
            StringBuilder hs = new StringBuilder();
            for (int i = 0; i < k.qubits; i++) {
                if (i > 0) hs.append(',');
                hs.append(k.hues[i]);
            }
            e.putString("hues", hs.toString());
            StringBuilder ps = new StringBuilder();
            for (int i = 0; i < k.qubits; i++) {
                if (i > 0) ps.append(',');
                ps.append(k.partner[i]);
            }
            e.putString("partners", ps.toString());
            e.putFloat("entropy", w.entropy);
            e.putFloat("grown", k.active != null ? k.active.grown : 1f);
            e.putFloat("pool", w.pool);
            e.putFloat("resonance", w.resonance);
        }
        e.putLong("saved", System.currentTimeMillis());
        e.apply();
    }
}
