package com.kingdomsofash.weapon;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.AnnounceFx;
import com.kingdomsofash.persist.StateStore;
import com.kingdomsofash.util.Audit;
import com.kingdomsofash.util.Keys;
import com.kingdomsofash.util.Text;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative ownership logic for Oathbreaker. Every ledger transition goes through here, is applied
 * exactly once (the ledger rejects a repeated transition) and is committed durably before it is announced.
 */
public final class WeaponService {
    public enum PickupVerdict { ALLOW_CLAIM, ALLOW_TRANSFER, DENY_REMOVE, DENY_KEEP }

    private final Plugin plugin;
    private final Settings s;
    private final StateStore store;
    private final Audit audit;
    private final OathbreakerItem factory;
    private final AnnounceFx announce;

    /** player -> tick at which a death / combat-log drop was sanctioned. */
    private final Map<UUID, Integer> sanctioned = new HashMap<>();
    /** item entities removed on purpose by us (so the removal listener does not "recover" them). */
    private final Set<UUID> ourRemovals = new HashSet<>();
    /** last time (ms) a player was damaged by another player. */
    private final Map<UUID, Long> lastPvpDamage = new HashMap<>();
    private int missingStreak;

    public WeaponService(Plugin plugin, Settings s, StateStore store, Audit audit, OathbreakerItem factory, AnnounceFx announce) {
        this.plugin = plugin;
        this.s = s;
        this.store = store;
        this.audit = audit;
        this.factory = factory;
        this.announce = announce;
    }

    public WeaponLedger ledger() {
        return store.state().weapon;
    }

    public OathbreakerItem items() {
        return factory;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ identity checks

    /** Valid signature AND current epoch. */
    public boolean isLegit(ItemStack stack) {
        OathbreakerItem.Identity id = factory.identify(stack);
        return id != null && ledger().isCurrent(id.id(), id.epoch());
    }

    public boolean isWielding(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        return ledger().isHeldBy(p.getUniqueId()) && isLegit(hand);
    }

    public ItemStack mint() {
        WeaponLedger l = ledger();
        return factory.create(l.weaponUuid(), l.epoch);
    }

    /** All slots (0..40) of the player's inventory holding anything that claims to be Oathbreaker. */
    public List<Integer> claimingSlots(Player p) {
        List<Integer> out = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (factory.claimsToBeOathbreaker(inv.getItem(i))) out.add(i);
        }
        return out;
    }

    public void noteStrike(Player victim) {
        lastPvpDamage.put(victim.getUniqueId(), now());
    }

    public boolean recentlyInCombat(UUID player) {
        Long t = lastPvpDamage.get(player);
        return t != null && now() - t <= s.combatLogSeconds * 1000L;
    }

    public void sanction(UUID player) {
        sanctioned.put(player, Bukkit.getCurrentTick());
    }

    public boolean isSanctioned(UUID player) {
        Integer t = sanctioned.get(player);
        return t != null && Bukkit.getCurrentTick() - t <= 2;
    }

    // ------------------------------------------------------------------ item entity handling

    public void decorate(Item it) {
        it.setUnlimitedLifetime(true);
        it.setInvulnerable(true);
        it.setCanMobPickup(false);
        it.setWillAge(false);
        it.setGlowing(true);
        it.customName(Text.mm("<dark_red><bold>☠ OATHBREAKER ☠"));
        it.setCustomNameVisible(true);
        it.getPersistentDataContainer().set(Keys.OATH_ENTITY, PersistentDataType.BYTE, (byte) 1);
        try {
            Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team t = sb.getTeam("ka_oathbreaker");
            if (t == null) {
                t = sb.registerNewTeam("ka_oathbreaker");
                t.color(NamedTextColor.DARK_RED);
            }
            t.addEntry(it.getUniqueId().toString());
        } catch (Exception e) {
            plugin.getLogger().fine("glow team unavailable: " + e.getMessage());
        }
    }

    public Item dropEntity(Location loc, boolean hover, int pickupDelay) {
        World w = loc.getWorld();
        internal = true;
        try {
            Item it = w.dropItem(loc, mint(), e -> {
                e.setVelocity(new Vector(0, 0, 0));
                e.setPickupDelay(pickupDelay);
                if (hover) e.setGravity(false);
            });
            decorate(it);
            return it;
        } finally {
            internal = false;
        }
    }

    private boolean internal;

    /** True while this service itself is spawning the item entity (the spawn listener must ignore it). */
    public boolean internalSpawn() {
        return internal;
    }

    public void audit(String category, String message) {
        audit.log(category, message);
    }

    /** Small audio-visual "the curse refuses" cue. */
    public void fxHint(Player p) {
        p.playSound(p.getLocation(), "block.chain.break", 0.7f, 0.6f);
        p.spawnParticle(org.bukkit.Particle.DUST, p.getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0,
                new org.bukkit.Particle.DustOptions(com.kingdomsofash.fx.Fx.BLOOD, 1.1f));
    }

    public void removeEntityQuietly(Entity e) {
        if (e == null) return;
        ourRemovals.add(e.getUniqueId());
        e.remove();
    }

    public boolean wasOurRemoval(UUID id) {
        return ourRemovals.remove(id);
    }

    public Item liveEntity() {
        UUID id = ledger().itemEntityUuid();
        if (id == null) return null;
        Entity e = Bukkit.getEntity(id);
        return e instanceof Item it && it.isValid() ? it : null;
    }

    // ------------------------------------------------------------------ reward

    /**
     * Ritual reward: mints the identity (once) and makes the blade appear at {@code at}. Idempotent - calling it
     * again while the reward is pending never creates a second entity.
     */
    public Item spawnReward(Location at) {
        WeaponLedger l = ledger();
        if (l.phase == WeaponLedger.Phase.NONE) {
            l.beginReward(UUID.randomUUID(), now());
        }
        if (l.phase != WeaponLedger.Phase.REWARD_PENDING) {
            audit.log("REWARD", "spawnReward ignored, ledger phase is " + l.phase);
            return null;
        }
        Item existing = liveEntity();
        if (existing != null) return existing;
        Item it = dropEntity(at, true, 20);
        l.relocate(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), it.getUniqueId());
        store.saveNow();
        audit.log("REWARD", "Oathbreaker materialised at " + fmt(at) + " entity=" + it.getUniqueId());
        return it;
    }

    /** After a restart: guarantee exactly one reward entity while the reward is pending. */
    public void ensureRewardEntity(Location altar) {
        WeaponLedger l = ledger();
        if (l.phase != WeaponLedger.Phase.REWARD_PENDING) return;
        if (liveEntity() != null) return;
        // Entity not found among loaded entities. Invalidate any ghost and make exactly one new one.
        l.bumpEpoch(now(), null, "system", "reward entity missing after restart");
        Item it = dropEntity(altar, true, 20);
        l.relocate(altar.getWorld().getName(), altar.getX(), altar.getY(), altar.getZ(), it.getUniqueId());
        store.saveNow();
        audit.log("RECOVERY", "reward entity re-created at altar, epoch=" + l.epoch);
    }

    // ------------------------------------------------------------------ pickup

    /** Stage 1 (HIGHEST, may cancel): pure validation, no ledger change. */
    public PickupVerdict validatePickup(Player player, Item entity) {
        ItemStack stack = entity.getItemStack();
        OathbreakerItem.Identity id = factory.identify(stack);
        WeaponLedger l = ledger();
        if (id == null) {
            audit.log("INTEGRITY", "forged Oathbreaker entity picked up by " + player.getName() + " - destroyed");
            return PickupVerdict.DENY_REMOVE;
        }
        if (!l.isCurrent(id.id(), id.epoch())) {
            audit.log("INTEGRITY", "stale/duplicate Oathbreaker entity (epoch " + id.epoch() + " vs " + l.epoch + ") touched by "
                    + player.getName() + " - destroyed");
            return PickupVerdict.DENY_REMOVE;
        }
        UUID rec = l.itemEntityUuid();
        if (rec != null && !rec.equals(entity.getUniqueId())) {
            audit.log("INTEGRITY", "second entity for the current epoch (" + entity.getUniqueId() + ") - destroyed");
            return PickupVerdict.DENY_REMOVE;
        }
        return switch (l.phase) {
            case REWARD_PENDING -> PickupVerdict.ALLOW_CLAIM;
            case DROPPED -> PickupVerdict.ALLOW_TRANSFER;
            default -> PickupVerdict.DENY_REMOVE;
        };
    }

    /** Stage 2 (MONITOR, event not cancelled): the pickup really happens - apply the ledger transition once. */
    public void commitPickup(Player player, PickupVerdict verdict, Item entity) {
        WeaponLedger l = ledger();
        long t = now();
        if (verdict == PickupVerdict.ALLOW_CLAIM) {
            if (l.claim(player.getUniqueId(), player.getName(), t) != WeaponLedger.ClaimResult.CLAIMED) return;
            boolean first = !l.claimAnnounced;
            l.claimAnnounced = true; // set before broadcasting: a crash can lose the show, never repeat it
            store.saveNow();
            audit.log("CLAIM", player.getName() + " (" + player.getUniqueId() + ") claimed Oathbreaker at " + fmt(player.getLocation()));
            missingStreak = 0;
            clearGlow(entity);
            if (first) announce.claim(player);
            plugin.getServer().getPluginManager().callEvent(new OathbreakerClaimedEvent(player));
        } else if (verdict == PickupVerdict.ALLOW_TRANSFER) {
            String prev = l.holderName;
            if (!l.pickup(player.getUniqueId(), player.getName(), t)) return;
            store.saveNow();
            audit.log("TRANSFER", "Oathbreaker " + prev + " -> " + player.getName() + " at " + fmt(player.getLocation()));
            missingStreak = 0;
            clearGlow(entity);
            if (s.broadcastOwnershipChange) announce.transfer(player);
        }
    }

    private void clearGlow(Item e) {
        try {
            Team t = Bukkit.getScoreboardManager().getMainScoreboard().getTeam("ka_oathbreaker");
            if (t != null) t.removeEntry(e.getUniqueId().toString());
        } catch (Exception ignored) {
            // cosmetic only
        }
    }

    // ------------------------------------------------------------------ drops

    /** A sanctioned (death / combat-log) or otherwise real item entity appeared: record where the blade is. */
    public void registerDroppedEntity(Item entity, String reason) {
        WeaponLedger l = ledger();
        Location loc = entity.getLocation();
        decorate(entity);
        if (l.phase == WeaponLedger.Phase.HELD) {
            String who = l.holderName;
            if (l.drop(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), entity.getUniqueId(), now(), reason)) {
                entity.setPickupDelay(0);
                store.saveNow();
                audit.log("DROP", "Oathbreaker dropped by " + who + " (" + reason + ") at " + fmt(loc));
            }
        } else if (l.phase == WeaponLedger.Phase.DROPPED || l.phase == WeaponLedger.Phase.REWARD_PENDING) {
            l.relocate(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), entity.getUniqueId());
            store.saveAsync();
        }
    }

    /** Called when the holder dies: handles keepInventory and swallowed drops. Returns true if a drop was arranged. */
    public void onHolderDeath(Player victim, List<ItemStack> drops, boolean keepInventory) {
        WeaponLedger l = ledger();
        if (!l.isHeldBy(victim.getUniqueId())) {
            drops.removeIf(i -> factory.claimsToBeOathbreaker(i) && !isLegit(i));
            return;
        }
        // remove duplicate / forged claims from the drop list, keep exactly one legit stack
        boolean haveLegit = false;
        List<ItemStack> toRemove = new ArrayList<>();
        for (ItemStack d : drops) {
            if (!factory.claimsToBeOathbreaker(d)) continue;
            if (isLegit(d) && !haveLegit) haveLegit = true;
            else toRemove.add(d);
        }
        drops.removeAll(toRemove);
        if (!haveLegit) {
            ItemStack found = null;
            PlayerInventory inv = victim.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (isLegit(st)) {
                    found = st.clone();
                    if (s.dropOnKeepInventory) inv.setItem(i, null);
                    break;
                }
            }
            if (found != null && s.dropOnKeepInventory) {
                drops.add(found);
                haveLegit = true;
                if (keepInventory) audit.log("DEATH", "keepInventory overridden for Oathbreaker held by " + victim.getName());
            }
        }
        if (haveLegit) sanction(victim.getUniqueId());
        // Whatever happens next (grave plugin, other drop-modifier), make sure the ledger leaves HELD.
        UUID vid = victim.getUniqueId();
        Location where = victim.getLocation().clone();
        Bukkit.getScheduler().runTaskLater(plugin, () -> verifyDeathDrop(vid, victim.getName(), where), 3L);
    }

    private void verifyDeathDrop(UUID victim, String name, Location where) {
        WeaponLedger l = ledger();
        if (!l.isHeldBy(victim)) return; // normal path: ItemSpawn already moved it to DROPPED
        Player online = Bukkit.getPlayer(victim);
        if (online != null && online.isOnline() && !online.isDead()) {
            // alive again (e.g. totem or respawn) - if the blade is still in the inventory all is well
            for (int i = 0; i < online.getInventory().getSize(); i++) {
                if (isLegit(online.getInventory().getItem(i))) return;
            }
        }
        // swallowed by a grave / death-chest plugin or lost: invalidate every old copy, re-drop one fresh blade
        l.bumpEpoch(now(), victim.toString(), name, "death drop was captured or lost");
        Item it = dropEntity(where, false, 0);
        l.drop(where.getWorld().getName(), where.getX(), where.getY(), where.getZ(), it.getUniqueId(), now(),
                "death drop recovered after another plugin captured it");
        store.saveNow();
        audit.log("RECOVERY", "death drop of " + name + " was captured by another plugin; epoch bumped to " + l.epoch
                + " and one fresh blade dropped at " + fmt(where));
    }

    /** Combat-log: holder disconnects shortly after PvP damage. */
    public void dropForCombatLog(Player p) {
        WeaponLedger l = ledger();
        if (!l.isHeldBy(p.getUniqueId())) return;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (isLegit(st)) {
                inv.setItem(i, null);
                sanction(p.getUniqueId());
                p.getWorld().dropItemNaturally(p.getLocation(), st); // ItemSpawnEvent registers it
                audit.log("COMBAT_LOG", p.getName() + " disconnected in combat; Oathbreaker dropped");
                return;
            }
        }
    }

    /** Void / despawn etc.: same identity continues, exactly one entity. */
    public void respawnLostEntity(UUID oldEntity, Location last) {
        WeaponLedger l = ledger();
        UUID rec = l.itemEntityUuid();
        if (rec == null || !rec.equals(oldEntity)) return;
        if (l.phase != WeaponLedger.Phase.DROPPED && l.phase != WeaponLedger.Phase.REWARD_PENDING) return;
        Location target = safeLocation(l, last);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (l.itemEntityUuid() == null || !l.itemEntityUuid().equals(oldEntity)) return;
            Item it = dropEntity(target, l.phase == WeaponLedger.Phase.REWARD_PENDING, 0);
            l.relocate(target.getWorld().getName(), target.getX(), target.getY(), target.getZ(), it.getUniqueId());
            store.saveNow();
            audit.log("RECOVERY", "item entity was destroyed (void/despawn); re-created at " + fmt(target));
        });
    }

    private Location safeLocation(WeaponLedger l, Location last) {
        World w = last != null ? last.getWorld() : null;
        if (w == null && l.dropWorld != null) w = Bukkit.getWorld(l.dropWorld);
        if (w == null) w = Bukkit.getWorlds().get(0);
        double x = last != null ? last.getX() : l.dropX;
        double z = last != null ? last.getZ() : l.dropZ;
        double y = last != null ? last.getY() : l.dropY;
        if (y < w.getMinHeight() + 2) {
            y = l.dropY > w.getMinHeight() + 2 ? l.dropY : w.getHighestBlockYAt((int) x, (int) z) + 1;
        }
        return new Location(w, x, y, z);
    }

    // ------------------------------------------------------------------ periodic verification

    /** Cheap: look at the single holder only. */
    public void verifyHolder() {
        WeaponLedger l = ledger();
        if (l.phase != WeaponLedger.Phase.HELD) {
            missingStreak = 0;
            return;
        }
        UUID id = l.holderUuid();
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p == null || !p.isOnline() || p.isDead()) {
            missingStreak = 0;
            return;
        }
        int found = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!factory.claimsToBeOathbreaker(st)) continue;
            if (isLegit(st) && found == 0) {
                found++;
            } else {
                inv.setItem(i, null);
                audit.log("INTEGRITY", "removed extra/forged Oathbreaker from " + p.getName() + " slot " + i);
            }
        }
        if (isLegit(p.getItemOnCursor())) found++;
        if (found > 0) {
            missingStreak = 0;
            return;
        }
        missingStreak++;
        if (missingStreak >= s.missingChecksBeforeRecovery) {
            missingStreak = 0;
            audit.log("INTEGRITY", "holder " + p.getName() + " no longer carries Oathbreaker (" + (s.autoRecoverMissing
                    ? "auto-recovering" : "auto-recovery disabled; use /oathbreaker recover") + ")");
            if (s.autoRecoverMissing) recoverToHolder(p, "system", "holder missing the blade");
        }
    }

    /** Whole-server sweep for illegitimate copies. Optional, low frequency. */
    public void integritySweep() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            purgeIllegitimate(p);
        }
    }

    /** Removes every Oathbreaker-claiming stack that is not the single legitimate carried item. */
    public int purgeIllegitimate(Player p) {
        int removed = 0;
        PlayerInventory inv = p.getInventory();
        boolean holderLegitSeen = false;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!factory.claimsToBeOathbreaker(st)) continue;
            boolean legit = isLegit(st) && ledger().isHeldBy(p.getUniqueId()) && !holderLegitSeen;
            if (legit) {
                holderLegitSeen = true;
            } else {
                inv.setItem(i, null);
                removed++;
                audit.log("INTEGRITY", "purged illegitimate Oathbreaker from " + p.getName() + " slot " + i);
            }
        }
        ItemStack cur = p.getItemOnCursor();
        if (factory.claimsToBeOathbreaker(cur) && !(isLegit(cur) && ledger().isHeldBy(p.getUniqueId()) && !holderLegitSeen)) {
            p.setItemOnCursor(null);
            removed++;
        }
        return removed;
    }

    // ------------------------------------------------------------------ admin recovery

    public void recoverToHolder(Player p, String actor, String reason) {
        WeaponLedger l = ledger();
        if (!l.exists()) return;
        invalidateOldEntity();
        l.bumpEpoch(now(), p.getUniqueId().toString(), actor, reason);
        l.phase = WeaponLedger.Phase.HELD;
        l.holderId = p.getUniqueId().toString();
        l.holderName = p.getName();
        l.holderSinceMs = now();
        giveFresh(p);
        store.saveNow();
        audit.log("RECOVERY", "Oathbreaker re-issued to " + p.getName() + " by " + actor + " (" + reason + "), epoch " + l.epoch);
    }

    private void giveFresh(Player p) {
        purgeIllegitimate(p);
        ItemStack fresh = mint();
        var leftover = p.getInventory().addItem(fresh);
        if (!leftover.isEmpty()) {
            // inventory full: swap into the first slot, spilling what was there (not the blade)
            ItemStack displaced = p.getInventory().getItem(0);
            p.getInventory().setItem(0, fresh);
            if (displaced != null) p.getWorld().dropItemNaturally(p.getLocation(), displaced);
        }
    }

    public void invalidateOldEntity() {
        UUID id = ledger().itemEntityUuid();
        if (id == null) return;
        Entity e = Bukkit.getEntity(id);
        if (e != null) removeEntityQuietly(e);
    }

    /** Admin: assign to a player (creating the identity if the weapon never existed). */
    public void adminGive(Player target, String actor) {
        WeaponLedger l = ledger();
        invalidateOldEntity();
        l.adminAssign(target.getUniqueId(), target.getName(), UUID.randomUUID(), now(), actor, "admin give");
        giveFresh(target);
        store.saveNow();
        audit.log("ADMIN", actor + " gave Oathbreaker to " + target.getName() + ", epoch " + l.epoch);
    }

    /** Admin: place the blade as a dropped item at a location. */
    public void adminDropAt(Location at, String actor) {
        WeaponLedger l = ledger();
        if (!l.exists()) {
            l.beginReward(UUID.randomUUID(), now());
        }
        invalidateOldEntity();
        l.bumpEpoch(now(), null, actor, "admin drop-at");
        Item it = dropEntity(at, false, 0);
        l.recoverAsDropped(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), it.getUniqueId(), now(), actor, "admin placement");
        store.saveNow();
        audit.log("ADMIN", actor + " placed Oathbreaker at " + fmt(at) + ", epoch " + l.epoch);
    }

    public static String fmt(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName()) + String.format(" %.1f %.1f %.1f", l.getX(), l.getY(), l.getZ());
    }

    public NamespacedKey entityKey() {
        return Keys.OATH_ENTITY;
    }
}
