package com.kingdomsofash.combat;

/** Pure damage-mitigation arithmetic used by Last King's Fury and by true damage. */
public final class Mitigation {
    private Mitigation() {
    }

    /**
     * Extra (negative) reduction that Fury adds on top of the vanilla modifiers.
     *
     * @param afterMitigation damage that remains after armor, enchantments and resistance
     *                        (BASE + HARD_HAT + BLOCKING + ARMOR + RESISTANCE + MAGIC, before absorption)
     * @param multiplier      0.90 for a 10% reduction
     * @return a value <= 0 to add to a modifier; e.g. 10 remaining -> -1.0 (so 9 are taken)
     */
    public static double furyAdjustment(double afterMitigation, double multiplier) {
        if (afterMitigation <= 0) return 0;
        return -(afterMitigation * (1.0 - multiplier));
    }

    /** Value of the ABSORPTION modifier for the given pre-absorption damage (always <= 0). */
    public static double absorptionModifier(double preAbsorption, double absorptionHearts) {
        if (preAbsorption <= 0 || absorptionHearts <= 0) return 0;
        return -Math.min(preAbsorption, absorptionHearts);
    }

    /** Health actually lost for a given modifier set. */
    public static double healthLoss(double preAbsorption, double absorptionHearts) {
        return Math.max(0, preAbsorption + absorptionModifier(preAbsorption, absorptionHearts));
    }
}
