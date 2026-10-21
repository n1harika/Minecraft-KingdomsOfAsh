package com.kingdomsofash.ritual;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.forge.ForgeService;
import com.kingdomsofash.ore.BloodironService;
import com.kingdomsofash.util.Keys;
import com.kingdomsofash.util.Text;
import com.kingdomsofash.weapon.OathbreakerClaimedEvent;
import com.kingdomsofash.weapon.WeaponService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Altar interaction, ritual-mob behaviour (targeting, containment, Endermen) and population tracking. */
public final class RitualListener implements Listener {
    private final Plugin plugin;
    private final Settings s;
    private final RitualManager ritual;
    private final ForgeService forge;
    private final BloodironService bloodiron;
    private final WeaponService weapons;

    public RitualListener(Plugin plugin, Settings s, RitualManager ritual, ForgeService forge, BloodironService bloodiron, WeaponService weapons) {
        this.plugin = plugin;
        this.s = s;
        this.ritual = ritual;
        this.forge = forge;
        this.bloodiron = bloodiron;
        this.weapons = weapons;
    }

    // ------------------------------------------------------------------ altar

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAltar(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (!forge.state().present()) return;
        Location altar = forge.altarLocation();
        if (altar == null) return;
        var b = e.getClickedBlock();
        if (!b.getWorld().equals(altar.getWorld()) || b.getX() != altar.getBlockX() || b.getY() != altar.getBlockY() || b.getZ() != altar.getBlockZ()) return;
        e.setCancelled(true); // never open the anvil GUI
        Player p = e.getPlayer();
        RitualManager.StartResult r = ritual.tryStart(p);
        switch (r) {
            case OK, RESUMED -> {
            }
            case NOT_READY -> Text.send(p, "<gray>The forge is still being raised from the ash.");
            case WEAPON_EXISTS -> {
                Text.send(p, "<gray>The anvil is cold. <dark_red>Oathbreaker<gray> has already been forged.");
                refuse(p, altar);
            }
            case IN_PROGRESS -> Text.send(p, "<gray>The trial is already underway. <red>Fight!");
            case COOLDOWN -> {
                int sec = ritual.retryRemainingSeconds();
                Text.send(p, "<gray>The runes are still cooling. Return in <red>" + (sec / 60) + "m " + (sec % 60) + "s<gray>.");
                refuse(p, altar);
            }
            case NO_MATERIAL -> {
                Text.send(p, "<gray>The altar demands <red>" + s.bloodironRequired + " Bloodiron Ore<gray>. You carry <white>"
                        + bloodiron.countAuthentic(p) + "<gray>. <dark_gray>(Silk Touch only)");
                refuse(p, altar);
            }
            case PERSIST_FAILED -> Text.send(p, "<red>The altar rejected the offering (internal error). Nothing was consumed.");
            case DISABLED -> Text.send(p, "<gray>The altar is dormant.");
        }
    }

    private void refuse(Player p, Location altar) {
        ritualFxRefuse(p, altar);
    }

    private void ritualFxRefuse(Player p, Location altar) {
        p.playSound(altar, "block.anvil.land", 0.5f, 0.5f);
        p.spawnParticle(org.bukkit.Particle.SMOKE, altar.clone().add(0.5, 1.4, 0.5), 12, 0.3, 0.3, 0.3, 0.03);
    }

    // ------------------------------------------------------------------ mobs

    @EventHandler(priority = EventPriority.HIGH)
    public void onMobDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        if (!ritual.isRitualMob(le)) return;
        if (!s.mobDrops) e.getDrops().clear();
        if (!s.mobXp) e.setDroppedExp(0);
        ritual.onMobDeath(le);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobRemoved(EntityRemoveEvent e) {
        Entity en = e.getEntity();
        if (!(en instanceof Mob) || !ritual.isRitualMob(en)) return;
        switch (e.getCause()) {
            case DESPAWN, OUT_OF_WORLD -> {
                if (ritual.trackedMobs().containsKey(en.getUniqueId())) {
                    java.util.UUID id = en.getUniqueId();
                    Bukkit.getScheduler().runTask(plugin, () -> ritual.onMobLost(id));
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!(e.getEntity() instanceof Mob m) || !ritual.isRitualMob(m)) return;
        if (e.getTarget() instanceof Player p && ritual.eligible(p)) return; // exactly what we want
        // outsiders, dead / creative / spectating players, other mobs, or "lost target": keep hunting a valid one
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (m.isValid() && ritual.active()) ritual.retarget(m);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEndermanTeleport(EntityTeleportEvent e) {
        if (!(e.getEntity() instanceof Enderman en) || !ritual.isRitualMob(en)) return;
        if (ritual.validArenaDestination(e.getTo())) return;
        Location alt = ritual.redirectTeleport(e.getFrom());
        if (alt != null) e.setTo(alt);
        else e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobBlockChange(EntityChangeBlockEvent e) {
        if (ritual.isRitualMob(e.getEntity())) e.setCancelled(true); // Endermen never pick up / place blocks
    }

    /** Ritual mobs do not hurt each other (arrows, creeper blasts) - waves must be cleared by the players. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent e) {
        if (!ritual.isRitualMob(e.getEntity())) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (ritual.isRitualMob(d)) e.setCancelled(true);
    }

    /** Orphans from a previous server run / dead encounter are removed as their chunk's entities load. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            String enc = en.getPersistentDataContainer().get(Keys.RITUAL_MOB, PersistentDataType.STRING);
            if (enc == null) continue;
            if (!ritual.active() || !enc.equals(ritual.encounterId()) || !ritual.trackedMobs().containsKey(en.getUniqueId())) {
                en.remove();
            }
        }
    }

    // ------------------------------------------------------------------ population changes

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        ritual.scheduleCheck();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ritual.scheduleCheck();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        ritual.scheduleCheck();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        ritual.scheduleCheck();
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        ritual.scheduleCheck();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemode(PlayerGameModeChangeEvent e) {
        ritual.scheduleCheck();
    }

    @EventHandler
    public void onClaimed(OathbreakerClaimedEvent e) {
        ritual.onClaimed();
    }
}
