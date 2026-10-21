package com.kingdomsofash.weapon;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The authoritative, Bukkit-free record of the one and only Oathbreaker.
 *
 * <p>Identity of an item = (weaponId, epoch). Whenever the weapon has to be re-materialised outside the
 * normal pick-up flow (administrator recovery, grave plugin swallowed it, ...) the epoch is incremented, which
 * instantly invalidates every older copy that might still exist somewhere (offline inventories, chests that
 * could not be scanned, other plugins' storage). That is what makes recovery unable to create a second
 * <em>legitimate</em> weapon.</p>
 *
 * <p>All mutation happens on the server thread; every transition method validates the current phase and
 * returns whether it was applied, so a duplicated or re-ordered event can never apply a transition twice.</p>
 */
public final class WeaponLedger {
    public enum Phase {
        /** The weapon has never been created (or the forge ritual has not been completed). */
        NONE,
        /** Dropped at the altar after the ritual; nobody has claimed it yet. */
        REWARD_PENDING,
        /** Carried by {@link #holderId}. */
        HELD,
        /** Lying in the world as an item entity (holder died / combat-logged / admin placed). */
        DROPPED
    }

    public enum ClaimResult { CLAIMED, REJECTED }

    public static final class Entry {
        public long timeMs;
        public String type;
        public String actorId;
        public String actorName;
        public String detail;

        public Entry() {
        }

        Entry(long t, String type, String actorId, String actorName, String detail) {
            this.timeMs = t;
            this.type = type;
            this.actorId = actorId;
            this.actorName = actorName;
            this.detail = detail;
        }
    }

    public static final int HISTORY_LIMIT = 500;

    public Phase phase = Phase.NONE;
    public String weaponId;
    public long epoch;

    public String claimantId;
    public String claimantName;
    public long claimedAtMs;
    /** RITUAL or ADMIN. */
    public String claimSource;
    public boolean claimAnnounced;

    public String holderId;
    public String holderName;
    public long holderSinceMs;

    /** Item entity when {@link Phase#DROPPED} or {@link Phase#REWARD_PENDING}. */
    public String itemEntityId;
    public String dropWorld;
    public double dropX;
    public double dropY;
    public double dropZ;

    /** Cooldowns are bound to the blade (wall clock, survive restarts). */
    public long ruptureReadyAtMs;
    public long ultimateReadyAtMs;

    public List<Entry> history = new ArrayList<>();

    public void normalise() {
        if (phase == null) phase = Phase.NONE;
        if (history == null) history = new ArrayList<>();
    }

    // ------------------------------------------------------------------ queries

    public boolean exists() {
        return phase != Phase.NONE;
    }

    public boolean isCurrent(UUID id, long itemEpoch) {
        return exists() && weaponId != null && weaponId.equals(id.toString()) && epoch == itemEpoch;
    }

    public boolean isHeldBy(UUID player) {
        return phase == Phase.HELD && holderId != null && holderId.equals(player.toString());
    }

    public UUID weaponUuid() {
        return weaponId == null ? null : UUID.fromString(weaponId);
    }

    public UUID holderUuid() {
        return holderId == null ? null : UUID.fromString(holderId);
    }

    public UUID itemEntityUuid() {
        return itemEntityId == null ? null : UUID.fromString(itemEntityId);
    }

    // ------------------------------------------------------------------ transitions

    /** NONE -> REWARD_PENDING. Mints the one and only weapon identity. */
    public boolean beginReward(UUID newId, long now) {
        if (phase != Phase.NONE) return false;
        weaponId = newId.toString();
        epoch = 1;
        phase = Phase.REWARD_PENDING;
        itemEntityId = null;
        log(now, "REWARD_CREATED", null, null, "weaponId=" + weaponId);
        return true;
    }

    /** REWARD_PENDING -> HELD. The first successful call wins; every later call is rejected. */
    public ClaimResult claim(UUID player, String name, long now) {
        if (phase != Phase.REWARD_PENDING) return ClaimResult.REJECTED;
        phase = Phase.HELD;
        if (claimantId == null) {
            claimantId = player.toString();
            claimantName = name;
            claimedAtMs = now;
            claimSource = "RITUAL";
        }
        holderId = player.toString();
        holderName = name;
        holderSinceMs = now;
        itemEntityId = null;
        log(now, "CLAIMED", player.toString(), name, "first pickup at the Lost Forge");
        return ClaimResult.CLAIMED;
    }

    /** DROPPED -> HELD: a different (or the same) player picked the blade up. */
    public boolean pickup(UUID player, String name, long now) {
        if (phase != Phase.DROPPED) return false;
        String prev = holderName;
        phase = Phase.HELD;
        holderId = player.toString();
        holderName = name;
        holderSinceMs = now;
        itemEntityId = null;
        log(now, "PICKUP", player.toString(), name, "previous holder: " + prev);
        return true;
    }

    /** HELD -> DROPPED (death, combat-log, admin). */
    public boolean drop(String world, double x, double y, double z, UUID entity, long now, String reason) {
        if (phase != Phase.HELD) return false;
        phase = Phase.DROPPED;
        itemEntityId = entity == null ? null : entity.toString();
        dropWorld = world;
        dropX = x;
        dropY = y;
        dropZ = z;
        log(now, "DROPPED", holderId, holderName, reason + String.format(" @ %s %.1f %.1f %.1f", world, x, y, z));
        return true;
    }

    /** Re-points a DROPPED / REWARD_PENDING record at a (new) item entity or position. */
    public boolean relocate(String world, double x, double y, double z, UUID entity) {
        if (phase != Phase.DROPPED && phase != Phase.REWARD_PENDING) return false;
        itemEntityId = entity == null ? null : entity.toString();
        if (world != null) {
            dropWorld = world;
            dropX = x;
            dropY = y;
            dropZ = z;
        }
        return true;
    }

    /**
     * Invalidates every previously issued copy. Returns the new epoch. The caller must afterwards materialise
     * exactly one new item (give / drop at altar) and record where it went.
     */
    public long bumpEpoch(long now, String actorId, String actorName, String reason) {
        epoch++;
        itemEntityId = null;
        log(now, "EPOCH_BUMP", actorId, actorName, "epoch=" + epoch + " (" + reason + ")");
        return epoch;
    }

    /** Administrator assignment of the blade to a holder (creates the identity if it never existed). */
    public void adminAssign(UUID target, String targetName, UUID newIdIfNone, long now, String actorName, String reason) {
        boolean fresh = phase == Phase.NONE;
        if (fresh) {
            weaponId = newIdIfNone.toString();
            epoch = 0;
        }
        bumpEpoch(now, null, actorName, reason);
        phase = Phase.HELD;
        holderId = target.toString();
        holderName = targetName;
        holderSinceMs = now;
        itemEntityId = null;
        if (claimantId == null) {
            claimantId = target.toString();
            claimantName = targetName;
            claimedAtMs = now;
            claimSource = "ADMIN";
            claimAnnounced = true;
        }
        log(now, "ADMIN_ASSIGN", target.toString(), targetName, "by " + actorName + ": " + reason);
    }

    /** Moves a recovered blade to a world position as a dropped item (epoch must already be bumped). */
    public void recoverAsDropped(String world, double x, double y, double z, UUID entity, long now, String actorName, String reason) {
        phase = Phase.DROPPED;
        itemEntityId = entity == null ? null : entity.toString();
        dropWorld = world;
        dropX = x;
        dropY = y;
        dropZ = z;
        log(now, "RECOVERED_DROPPED", null, actorName, reason);
    }

    public void log(long now, String type, String actorId, String actorName, String detail) {
        history.add(new Entry(now, type, actorId, actorName, detail));
        while (history.size() > HISTORY_LIMIT) history.remove(0);
    }
}
