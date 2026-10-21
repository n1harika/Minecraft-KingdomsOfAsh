package com.kingdomsofash.combat;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.RuptureFx;
import com.kingdomsofash.persist.StateStore;
import com.kingdomsofash.weapon.WeaponLedger;
import com.kingdomsofash.weapon.WeaponService;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Ability one: Rupture. */
public final class RuptureService {
    public enum Result { OK, NOT_WIELDING, NO_RAGE, COOLDOWN, CANCELLED }

    private final Settings s;
    private final WeaponService weapons;
    private final RageManager rage;
    private final BleedManager bleed;
    private final StateStore store;
    private final RuptureFx fx;

    public RuptureService(Settings s, WeaponService weapons, RageManager rage, BleedManager bleed, StateStore store, RuptureFx fx) {
        this.s = s;
        this.weapons = weapons;
        this.rage = rage;
        this.bleed = bleed;
        this.store = store;
        this.fx = fx;
    }

    public long cooldownLeftMs() {
        return Math.max(0, weapons.ledger().ruptureReadyAtMs - System.currentTimeMillis());
    }

    public Result cast(Player p) {
        if (!weapons.isWielding(p) || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return Result.NOT_WIELDING;
        long left = cooldownLeftMs();
        if (left > 0) {
            fx.cooldown(p, (long) Math.ceil(left / 1000.0));
            return Result.COOLDOWN;
        }
        int have = rage.rage(p.getUniqueId());
        if (have < s.ruptureRageCost) {
            fx.noRage(p, s.ruptureRageCost, have);
            return Result.NO_RAGE;
        }
        OathbreakerAbilityEvent ev = new OathbreakerAbilityEvent(p, OathbreakerAbilityEvent.Ability.RUPTURE);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return Result.CANCELLED;

        // ---- committed: Rage and cooldown are consumed even if nothing is hit
        rage.spend(p, s.ruptureRageCost);
        WeaponLedger l = weapons.ledger();
        l.ruptureReadyAtMs = System.currentTimeMillis() + Math.round(s.ruptureCooldownSeconds * 1000);
        store.saveAsync();

        fx.slash(p, s.ruptureRange, s.ruptureWidth);
        List<LivingEntity> targets = findTargets(p);
        Set<UUID> done = new HashSet<>(); // each target at most once per cast
        for (LivingEntity t : targets) {
            if (!done.add(t.getUniqueId())) continue;
            strike(p, t);
        }
        return Result.OK;
    }

    private void strike(Player p, LivingEntity t) {
        if (s.ruptureShieldBlocks && isShieldBlocking(t, p)) {
            fx.shieldBlock(t, p);
            return; // no damage, no bleed
        }
        boolean landed = TrueDamage.apply(t, p, s.ruptureInitialHearts * 2.0, "RUPTURE");
        if (!landed) return; // cancelled by a protection plugin or absorbed completely
        fx.hit(t);
        if (!t.isDead()) bleed.apply(t, p);
    }

    /** Vanilla shield rule: blocking and the attacker is within the 180 degree arc the victim faces. */
    static boolean isShieldBlocking(LivingEntity t, Player attacker) {
        if (!(t instanceof Player v) || !v.isBlocking()) return false;
        Vector look = v.getLocation().getDirection().setY(0);
        Vector to = attacker.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (look.lengthSquared() < 1e-9 || to.lengthSquared() < 1e-9) return true;
        return to.normalize().dot(look.normalize()) > 0;
    }

    public List<LivingEntity> findTargets(Player p) {
        List<LivingEntity> out = new ArrayList<>();
        Location feet = p.getLocation();
        World w = feet.getWorld();
        double reach = Math.hypot(s.ruptureRange, s.ruptureWidth / 2.0) + 1.5;
        Vector dir = RuptureGeometryBridge.forward(feet.getYaw());
        Location center = feet.clone().add(dir.clone().multiply(s.ruptureRange / 2.0));
        for (Entity e : w.getNearbyEntities(center, reach, reach + 3, reach)) {
            if (!(e instanceof LivingEntity le) || !eligible(p, le)) continue;
            BoundingBox b = le.getBoundingBox();
            if (!RuptureGeometry.hits(feet.getX(), feet.getY(), feet.getZ(), feet.getYaw(), s.ruptureRange, s.ruptureWidth,
                    s.ruptureVerticalBelow, s.ruptureVerticalAbove,
                    b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ())) continue;
            if (s.ruptureLineOfSight && !hasLineOfSight(p, le)) continue;
            out.add(le);
        }
        return out;
    }

    private boolean eligible(Player p, LivingEntity le) {
        if (le.equals(p) || le instanceof ArmorStand || !le.isValid() || le.isDead() || le.isInvulnerable()) return false;
        if (le instanceof Player other) {
            if (other.getGameMode() == GameMode.CREATIVE || other.getGameMode() == GameMode.SPECTATOR) return false;
        }
        if (s.ruptureIgnorePets && le instanceof Tameable tm && tm.isTamed() && p.equals(tm.getOwner())) return false;
        if (s.ruptureTeamFriendlyFire) {
            var sb = Bukkit.getScoreboardManager().getMainScoreboard();
            Team a = sb.getEntryTeam(p.getName());
            String entry = le instanceof Player op ? op.getName() : le.getUniqueId().toString();
            Team b = sb.getEntryTeam(entry);
            if (a != null && a.equals(b) && !a.allowFriendlyFire()) return false;
        }
        return true;
    }

    private boolean hasLineOfSight(Player p, LivingEntity t) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Location[] samples = {t.getLocation().add(0, t.getHeight() * 0.5, 0), t.getEyeLocation(), t.getLocation().add(0, 0.2, 0)};
        for (Location s2 : samples) {
            Vector d = s2.toVector().subtract(eye.toVector());
            double dist = d.length();
            if (dist < 0.5) return true;
            RayTraceResult r = w.rayTraceBlocks(eye, d.normalize(), dist, FluidCollisionMode.NEVER, true);
            if (r == null || r.getHitBlock() == null) return true;
        }
        return false;
    }

    /** Tiny shim so the forward-vector maths is shared with the tested geometry. */
    static final class RuptureGeometryBridge {
        static Vector forward(double yaw) {
            double[] f = RuptureGeometry.forward(yaw);
            return new Vector(f[0], 0, f[1]);
        }
    }
}
