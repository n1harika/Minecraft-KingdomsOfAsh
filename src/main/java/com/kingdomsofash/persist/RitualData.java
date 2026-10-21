package com.kingdomsofash.persist;

/** Persisted ritual state machine data. */
public final class RitualData {
    public enum Phase {
        /** Forge idle and available. */
        IDLE,
        /** Ore consumed, intro sequence running. */
        STARTING,
        /** A wave's mobs are alive. */
        WAVE_ACTIVE,
        /** Wave cleared, next wave pending. */
        WAVE_TRANSITION,
        /** Final wave cleared, dramatic reveal running. */
        REWARD_SEQUENCE,
        /** Oathbreaker physically present at the altar, unclaimed. */
        REWARD_PENDING,
        /** Oathbreaker claimed. The forge is silent. */
        COMPLETED,
        /** Failed; waiting for retry cooldown. */
        FAILED,
        /** Server restarted mid-trial; the altar resumes the same wave. */
        INTERRUPTED
    }

    public Phase phase = Phase.IDLE;
    public String encounterId;
    /** 1-based wave currently active / most recently started. */
    public int wave;
    public String initiatorId;
    public String initiatorName;
    public long startedAtMs;
    public long retryAvailableAtMs;
    public String lastFailReason;
    public long attempts;
    public long lastTransitionMs;

    public boolean inProgress() {
        return switch (phase) {
            case STARTING, WAVE_ACTIVE, WAVE_TRANSITION, REWARD_SEQUENCE -> true;
            default -> false;
        };
    }
}
