package com.shumtugle.amalgam;

/**
 * Listens to the order of observations and notices when it repeats.
 *
 * Every observation becomes a symbol: which side of the crystal the drop was on (four sides)
 * and whether it landed on the breath (two ways), eight symbols in all. A pattern is a run of
 * three or four symbols played twice in a row, with at least two different symbols in it.
 * Runs of two repeat by chance too often to mean anything.
 *
 * A pattern once heard is remembered and earns nothing while remembered; memory fades, so an
 * old pattern works again after a while. The player has to keep finding new ones.
 */
final class Listener {
    static final int SYMBOLS = 8;
    private static final int HISTORY = 16;
    /** Seconds a heard pattern stays familiar. */
    static final float HABIT = 300f;
    private static final int MEMORY = 64;

    private final int[] last = new int[HISTORY];
    private int count;

    private final int[] heardKey = new int[MEMORY];
    private final float[] heardAt = new float[MEMORY];
    private int heardCount;

    /** The pattern found by the last call to hear(), oldest symbol first. */
    final int[] pattern = new int[4];
    int patternLength;

    /** How many symbols are in the current run, since the last pattern. */
    int length() {
        return count;
    }

    /** Symbol i of the current run, oldest first. */
    int at(int i) {
        return last[i];
    }

    static int symbol(int side, boolean onBeat) {
        return side * 2 + (onBeat ? 1 : 0);
    }

    /**
     * Takes one more observation at time now. Returns the length of a new pattern it completes,
     * or zero; the pattern itself is left in pattern[].
     */
    int hear(int symbol, float now) {
        if (count == HISTORY) {
            System.arraycopy(last, 1, last, 0, HISTORY - 1);
            count--;
        }
        last[count++] = symbol;
        // longest first: a repeated run of four outranks a shorter one inside it
        for (int n = 4; n >= 3; n--) {
            if (count < n * 2) continue;
            boolean same = true, varied = false;
            for (int i = 0; i < n; i++) {
                int a = last[count - n + i], b = last[count - 2 * n + i];
                if (a != b) { same = false; break; }
                if (a != last[count - n]) varied = true;
            }
            if (!same || !varied) continue;
            int key = key(count - n, n);
            if (familiar(key, now)) continue;
            remember(key, now);
            for (int i = 0; i < n; i++) pattern[i] = last[count - n + i];
            patternLength = n;
            // a heard repetition is spent; it cannot be counted again with one more symbol
            count = 0;
            return n;
        }
        return 0;
    }

    /** The same loop started at a different point is the same pattern: key by its smallest rotation. */
    private int key(int from, int n) {
        int best = Integer.MAX_VALUE;
        for (int r = 0; r < n; r++) {
            int k = n;
            for (int i = 0; i < n; i++) k = k * SYMBOLS + last[from + (r + i) % n];
            best = Math.min(best, k);
        }
        return best;
    }

    private boolean familiar(int key, float now) {
        for (int i = 0; i < heardCount; i++)
            if (heardKey[i] == key && now - heardAt[i] < HABIT) return true;
        return false;
    }

    private void remember(int key, float now) {
        for (int i = 0; i < heardCount; i++) {
            if (heardKey[i] == key || now - heardAt[i] >= HABIT) {
                heardKey[i] = key;
                heardAt[i] = now;
                return;
            }
        }
        if (heardCount < MEMORY) {
            heardKey[heardCount] = key;
            heardAt[heardCount] = now;
            heardCount++;
        }
    }
}
