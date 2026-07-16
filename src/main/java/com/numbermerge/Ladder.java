package com.numbermerge;

/**
 * The fixed progression of legal tile values: 2, 4, 8, ..., 1024, then 2k, 4k, ..., 1024k,
 * then 2m, 4m, ..., and so on. Addressed by a single non-negative "rung index"
 * (0 = the value 2, 9 = 1024, 10 = 2k, 19 = 1024k, 20 = 2m, ...).
 *
 * Each tier's multiplier is 1000x the last (thousand/million/billion/...), so the raw
 * value resets to a round number at every tier boundary rather than continuing a pure
 * binary doubling (e.g. 1024 -> 2k is 1024 -> 2000, not 1024 -> 2048). That's intentional.
 */
public final class Ladder {
    private static final long[] MANTISSAS = {2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};
    private static final String[] TIER_SUFFIXES = {
            "", "k", "m", "b", "t", "q", "Qi", "Sx", "Sp", "Oc", "No", "Dc"
    };

    private Ladder() {
    }

    public static long valueAt(int rungIndex) {
        int tier = rungIndex / MANTISSAS.length;
        int mantissaIdx = rungIndex % MANTISSAS.length;
        return MANTISSAS[mantissaIdx] * tierMultiplier(tier);
    }

    public static String label(int rungIndex) {
        int tier = rungIndex / MANTISSAS.length;
        int mantissaIdx = rungIndex % MANTISSAS.length;
        return MANTISSAS[mantissaIdx] + tierSuffix(tier);
    }

    /** Rounds an arbitrary positive value to the nearest legal rung. Ties round up. */
    public static int rungIndexForValue(long targetValue) {
        int idx = 0;
        while (valueAt(idx) < targetValue) {
            idx++;
        }
        if (idx == 0) {
            return 0;
        }
        long upper = valueAt(idx);
        long lower = valueAt(idx - 1);
        return (targetValue - lower < upper - targetValue) ? idx - 1 : idx;
    }

    private static long tierMultiplier(int tier) {
        long multiplier = 1;
        for (int i = 0; i < tier; i++) {
            multiplier *= 1000;
        }
        return multiplier;
    }

    private static String tierSuffix(int tier) {
        if (tier < TIER_SUFFIXES.length) {
            return TIER_SUFFIXES[tier];
        }
        // Falls back gracefully if play ever somehow runs past the named tiers.
        return "e" + (tier * 3);
    }
}
