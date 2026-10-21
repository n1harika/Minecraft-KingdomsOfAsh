package com.kingdomsofash.persist;

/** Persisted description of the (single) Lost Forge. The blueprint is a pure function of these fields. */
public final class ForgeState {
    public enum Status { NONE, GENERATING, READY }

    public Status status = Status.NONE;
    public String world;
    public int centerX;
    public int floorY;
    public int centerZ;
    public int radius;
    public int height;
    public long seed;
    /** Entrance direction: 0=+X, 1=+Z, 2=-X, 3=-Z. */
    public int dir;
    /** Horizontal length of the descending stair tunnel (also its vertical drop). */
    public int stairLength;
    public int surfaceY;
    public long plannedAtMs;
    public long builtAtMs;
    public int buildRevision;

    public int altarX() {
        return centerX;
    }

    public int altarY() {
        return floorY + 3;
    }

    public int altarZ() {
        return centerZ;
    }

    public boolean present() {
        return status != Status.NONE && world != null;
    }

    public boolean ready() {
        return status == Status.READY;
    }
}
