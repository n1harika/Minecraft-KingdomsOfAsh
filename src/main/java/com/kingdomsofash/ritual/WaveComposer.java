package com.kingdomsofash.ritual;

import java.util.Random;

/** Pure weighted composition of a wave: always returns exactly {@code total} mobs. */
public final class WaveComposer {
    public static final int ZOMBIE = 0;
    public static final int SKELETON = 1;
    public static final int CREEPER = 2;
    public static final int ENDERMAN = 3;
    public static final int TYPES = 4;

    private WaveComposer() {
    }

    /**
     * @param weights per-type weights (index = type)
     * @param caps    per-type maximum (use Integer.MAX_VALUE for none)
     * @return counts per type, summing to exactly {@code total}
     */
    public static int[] compose(double[] weights, int[] caps, int total, Random rnd) {
        int[] counts = new int[TYPES];
        for (int i = 0; i < total; i++) {
            double sum = 0;
            double[] eff = new double[TYPES];
            for (int t = 0; t < TYPES; t++) {
                eff[t] = counts[t] >= caps[t] ? 0 : Math.max(0, weights[t]);
                sum += eff[t];
            }
            int pick;
            if (sum <= 0) {
                pick = ZOMBIE; // degenerate configuration: the zombie is the unconditional fallback
            } else {
                double r = rnd.nextDouble() * sum;
                pick = ZOMBIE;
                for (int t = 0; t < TYPES; t++) {
                    if (eff[t] <= 0) continue;
                    r -= eff[t];
                    pick = t;
                    if (r < 0) break;
                }
            }
            counts[pick]++;
        }
        return counts;
    }
}
