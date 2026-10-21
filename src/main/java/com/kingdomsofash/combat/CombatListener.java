package com.kingdomsofash.combat;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.WeaponFx;
import com.kingdomsofash.util.Text;
import com.kingdomsofash.weapon.WeaponService;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Hooks the damage pipeline: Blood Oath hits, Fury / Exhaustion modifiers, true damage, cleanup. */
public final class CombatListener implements Listener {
    private static final DamageModifier[] PRE_ABSORPTION = {DamageModifier.BASE, DamageModifier.HARD_HAT, DamageModifier.BLOCKING,
            DamageModifier.ARMOR, DamageModifier.RESISTANCE, DamageModifier.MAGIC};

    private final Plugin plugin;
    private final Settings s;
    private final WeaponService weapons;
    private final RageManager rage;
    private final BleedManager bleed;
    private final FuryManager fury;
    private final SpectralArmor armor;
    private final WeaponFx weaponFx;

    public CombatListener(Plugin plugin, Settings s, WeaponService weapons, RageManager rage, BleedManager bleed, FuryManager fury,
                          SpectralArmor armor, WeaponFx weaponFx) {
        this.plugin = plugin;
        this.s = s;
        this.weapons = weapons;
        this.rage = rage;
        this.bleed = bleed;
        this.fury = fury;
        this.armor = armor;
        this.weaponFx = weaponFx;
    }

    // ------------------------------------------------------------------ outgoing: Fury +25% / Exhaustion -15%

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void outgoing(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        DamageCause c = e.getCause();
        if (c != DamageCause.ENTITY_ATTACK && c != DamageCause.ENTITY_SWEEP_ATTACK) return;
        double mult = 1.0;
        if (fury.isFury(p.getUniqueId())) mult *= s.furyDamageMultiplier;
        else if (fury.isExhausted(p.getUniqueId())) mult *= s.exhaustionDamageMultiplier;
        if (mult != 1.0) e.setDamage(e.getDamage() * mult);
    }

    // ------------------------------------------------------------------ true damage normalisation (before Fury incoming)

    @EventHandler(priority = EventPriority.HIGH)
    public void trueDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof LivingEntity le && TrueDamage.isActiveFor(le)) TrueDamage.normalise(e);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void fall(EntityDamageEvent e) {
        if (e.getCause() != DamageCause.FALL || !s.furyPreventFall) return;
        if (e.getEntity() instanceof Player p && fury.isFury(p.getUniqueId())) {
            e.setCancelled(true);
            p.setFallDistance(0);
        }
    }

    // ------------------------------------------------------------------ incoming: Fury -10% AFTER armor/enchants

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void incoming(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !fury.isFury(p.getUniqueId())) return;
        double pre = 0;
        for (DamageModifier m : PRE_ABSORPTION) {
            if (e.isApplicable(m)) pre += e.getDamage(m);
        }
        if (pre <= 0) return;
        double adj = Mitigation.furyAdjustment(pre, s.furyDamageTakenMultiplier);
        if (e.isApplicable(DamageModifier.MAGIC)) {
            e.setDamage(DamageModifier.MAGIC, e.getDamage(DamageModifier.MAGIC) + adj);
        } else if (e.isApplicable(DamageModifier.RESISTANCE)) {
            e.setDamage(DamageModifier.RESISTANCE, e.getDamage(DamageModifier.RESISTANCE) + adj);
        } else {
            return;
        }
        if (e.isApplicable(DamageModifier.ABSORPTION)) {
            e.setDamage(DamageModifier.ABSORPTION, Mitigation.absorptionModifier(pre + adj, p.getAbsorptionAmount()));
        }
    }

    // ------------------------------------------------------------------ Blood Oath: valid hits

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void validHit(EntityDamageByEntityEvent e) {
        if (e.getCause() != DamageCause.ENTITY_ATTACK || !(e.getDamager() instanceof Player p)) return;
        Entity victim = e.getEntity();
        if (!(victim instanceof LivingEntity) || victim instanceof ArmorStand || victim.equals(p)) return;
        if (e.getFinalDamage() <= 0) return; // fully absorbed / blocked
        if (!weapons.isWielding(p)) return;
        rage.registerHit(p, victim.getUniqueId());
    }

    // ------------------------------------------------------------------ lifecycle cleanup

    @EventHandler(priority = EventPriority.MONITOR)
    public void anyDeath(EntityDeathEvent e) {
        bleed.remove(e.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void playerDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        fury.onDeath(p);
        rage.clear(p.getUniqueId());
        TrueDamage.Mark m = TrueDamage.markFor(p.getUniqueId());
        if (m != null) {
            String victim = Text.esc(p.getName());
            String attacker = Text.esc(m.attackerName());
            e.deathMessage(Text.mm("BLEED".equals(m.kind())
                    ? "<red>" + victim + "<gray> bled out from <dark_red>" + attacker + "<gray>'s Oathbreaker"
                    : "<red>" + victim + "<gray> was cleaved apart by <dark_red>" + attacker + "<gray>'s Oathbreaker"));
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        fury.onJoin(e.getPlayer());
        bleed.onJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        fury.onQuit(p);
        bleed.onQuit(p);
        rage.clear(p.getUniqueId());
    }

    @EventHandler
    public void worldChange(PlayerChangedWorldEvent e) {
        fury.onWorldChange(e.getPlayer());
    }

    @EventHandler
    public void armorChange(PlayerArmorChangeEvent e) {
        Player p = e.getPlayer();
        if (armor.isWearing(p.getUniqueId())) Bukkit.getScheduler().runTask(plugin, () -> armor.refresh(p));
    }

    // ------------------------------------------------------------------ presentation hooks

    @EventHandler(ignoreCancelled = true)
    public void swing(PlayerAnimationEvent e) {
        if (!s.swingTrail || e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (weapons.isWielding(p)) weaponFx.swingTrail(p);
    }

    @EventHandler
    public void held(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (weapons.isLegit(p.getInventory().getItem(e.getNewSlot())) && weapons.ledger().isHeldBy(p.getUniqueId())) {
            var m = rage.model(p.getUniqueId());
            weaponFx.show(p, m.rage(), m.max());
        }
    }
}
