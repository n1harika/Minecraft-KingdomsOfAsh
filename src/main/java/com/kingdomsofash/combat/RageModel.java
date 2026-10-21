package com.kingdomsofash.combat;

/**
 * Pure Blood Oath state machine (no Bukkit). Time is passed in explicitly so it is deterministic and testable.
 *
 * <ul>
 *   <li>every {@code hitsPerRage} valid hits in a chain grant 1 Rage, capped at {@code maxRage};</li>
 *   <li>once {@code decayDelayMs} passes without a valid hit the chain is broken (partial progress is lost)
 *       and decay begins, losing one Rage immediately and then one per {@code decayIntervalMs};</li>
 *   <li>being hit never touches this model.</li>
 * </ul>
 */
public final class RageModel {
    public enum Change { NONE, GAINED, GAINED_MAX, LOST }

    private final int hitsPerRage;
    private final int maxRage;
    private final long decayDelayMs;
    private final long decayIntervalMs;

    private int rage;
    private int progress;
    private long lastHitMs = Long.MIN_VALUE;
    private long nextDecayMs;
    private boolean decaying;

    public RageModel(int hitsPerRage, int maxRage, long decayDelayMs, long decayIntervalMs) {
        this.hitsPerRage = Math.max(1, hitsPerRage);
        this.maxRage = Math.max(1, maxRage);
        this.decayDelayMs = Math.max(0, decayDelayMs);
        this.decayIntervalMs = Math.max(1, decayIntervalMs);
    }

    public int rage() {
        return rage;
    }

    public int progress() {
        return progress;
    }

    public int max() {
        return maxRage;
    }

    public boolean decaying() {
        return decaying;
    }

    public boolean idle() {
        return rage == 0 && progress == 0;
    }

    /** Registers one valid hit. */
    public Change hit(long now) {
        if (lastHitMs != Long.MIN_VALUE && now - lastHitMs >= decayDelayMs) {
            progress = 0; // the chain was broken
        }
        lastHitMs = now;
        decaying = false;
        if (rage >= maxRage) {
            progress = 0;
            return Change.NONE;
        }
        progress++;
        if (progress >= hitsPerRage) {
            progress = 0;
            rage++;
            return rage >= maxRage ? Change.GAINED_MAX : Change.GAINED;
        }
        return Change.NONE;
    }

    /** Advances decay. Call frequently (every few ticks). */
    public Change tick(long now) {
        if (idle() || lastHitMs == Long.MIN_VALUE) return Change.NONE;
        if (now - lastHitMs < decayDelayMs) return Change.NONE;
        if (!decaying) {
            decaying = true;
            progress = 0;
            nextDecayMs = lastHitMs + decayDelayMs;
        }
        if (rage > 0 && now >= nextDecayMs) {
            rage--;
            nextDecayMs += decayIntervalMs;
            if (nextDecayMs < now) nextDecayMs = now + decayIntervalMs; // never "catch up" in bursts
            return Change.LOST;
        }
        return Change.NONE;
    }

    /** @return true if {@code n} Rage were available and have been consumed. */
    public boolean spend(int n) {
        if (n < 0 || rage < n) return false;
        rage -= n;
        return true;
    }

    public int spendAll() {
        int r = rage;
        rage = 0;
        progress = 0;
        return r;
    }

    public void clear() {
        rage = 0;
        progress = 0;
        decaying = false;
        lastHitMs = Long.MIN_VALUE;
    }
}
