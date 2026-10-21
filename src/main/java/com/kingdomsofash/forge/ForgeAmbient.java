package com.kingdomsofash.forge;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.RitualFx;
import com.kingdomsofash.fx.WeaponFx;
import com.kingdomsofash.ritual.RitualManager;
import com.kingdomsofash.weapon.WeaponLedger;
import com.kingdomsofash.weapon.WeaponService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Cheap ambience. The forge only animates while a player is within range (one scan of the forge world's player
 * list, never of entities) and the effect budget in {@code Fx} sheds decorative particles under load.
 */
public final class ForgeAmbient {
    private final Plugin plugin;
    private final Settings s;
    private final ForgeService forge;
    private final RitualManager ritual;
    private final WeaponService weapons;
    private final RitualFx rfx;
    private final WeaponFx weaponFx;
    private BukkitTask task;
    private long tick;

    public ForgeAmbient(Plugin plugin, Settings s, ForgeService forge, RitualManager ritual, WeaponService weapons, RitualFx rfx, WeaponFx weaponFx) {
        this.plugin = plugin;
        this.s = s;
        this.forge = forge;
        this.ritual = ritual;
        this.weapons = weapons;
        this.rfx = rfx;
        this.weaponFx = weaponFx;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::run, 40L, Math.max(2, s.ambientInterval));
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private void run() {
        tick += Math.max(2, s.ambientInterval);
        if (!s.ambientEnabled || !forge.ready()) return;
        World w = forge.world();
        Location altar = forge.altarLocation();
        if (w == null || altar == null) return;
        double best = Double.MAX_VALUE;
        Location c = altar.clone().add(0.5, 0.5, 0.5);
        for (Player p : w.getPlayers()) {
            double d = p.getLocation().distanceSquared(c);
            if (d < best) best = d;
        }
        double range = s.ambientRange;
        if (best > range * range) return;
        double prox = Math.max(0, Math.min(1, 1.0 - Math.sqrt(best) / 14.0));
        rfx.ambient(altar, forge.state().radius, prox, tick);
    }

    /** Crimson pillar over an unclaimed or dropped blade. */
    public void beaconTick() {
        if (!s.droppedBeam) return;
        WeaponLedger l = weapons.ledger();
        if (l.phase != WeaponLedger.Phase.DROPPED && l.phase != WeaponLedger.Phase.REWARD_PENDING) return;
        Item it = weapons.liveEntity();
        if (it != null) weaponFx.beam(it.getLocation());
    }
}
