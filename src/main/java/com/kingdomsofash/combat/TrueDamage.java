package com.kingdomsofash.combat;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Armor- and enchantment-bypassing damage that still travels through the real damage pipeline.
 *
 * <p>Why not {@code setHealth}? Direct health edits skip {@link EntityDamageEvent}, so protection plugins,
 * totems, kill credit and death messages would all be bypassed. Instead we call {@code victim.damage(..)} with a
 * magic-type {@link DamageSource} whose only entity is the attacker (no direct entity -> no knockback), and an
 * event listener ({@link #normalise}) rewrites the event: BASE = requested amount, ARMOR / RESISTANCE / MAGIC /
 * HARD_HAT / BLOCKING = 0, ABSORPTION recomputed. A cancelled event (safe zone, region protection, ally rules)
 * therefore cancels the true damage as well.</p>
 */
public final class TrueDamage {
    public record Mark(String attackerName, String kind, int tick) {
    }

    private record Context(LivingEntity victim, double amount) {
    }

    private static Context active;
    private static final Map<UUID, Mark> MARKS = new HashMap<>();

    private TrueDamage() {
    }

    public static boolean isActiveFor(LivingEntity victim) {
        return active != null && active.victim == victim;
    }

    public static Mark markFor(UUID victim) {
        Mark m = MARKS.get(victim);
        if (m != null && Bukkit.getCurrentTick() - m.tick > 2) {
            MARKS.remove(victim);
            return null;
        }
        return m;
    }

    /** @return true if health/absorption was actually reduced (or the victim died). */
    public static boolean apply(LivingEntity victim, Player attacker, double amount, String kind) {
        if (victim.isDead() || !victim.isValid() || victim.isInvulnerable() || amount <= 0) return false;
        if (victim instanceof Player p && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        int prevTicks = victim.getNoDamageTicks();
        double prevLast = victim.getLastDamage();
        double before = victim.getHealth() + victim.getAbsorptionAmount();
        DamageSource.Builder b = DamageSource.builder(DamageType.MAGIC);
        if (attacker != null && attacker.isOnline()) b.withCausingEntity(attacker);
        DamageSource src = b.build();
        if (attacker != null) MARKS.put(victim.getUniqueId(), new Mark(attacker.getName(), kind, Bukkit.getCurrentTick()));
        active = new Context(victim, amount);
        try {
            victim.setNoDamageTicks(0); // true damage ignores invulnerability frames
            victim.damage(amount, src);
        } finally {
            active = null;
        }
        boolean dead = victim.isDead();
        if (!dead) {
            victim.setNoDamageTicks(prevTicks); // and does not grant them
            victim.setLastDamage(prevLast);
        }
        double after = victim.getHealth() + victim.getAbsorptionAmount();
        return dead || after < before - 1e-6;
    }

    /** Called from the damage listener (priority HIGH) for events raised by {@link #apply}. */
    public static void normalise(EntityDamageEvent e) {
        if (active == null || !(e.getEntity() instanceof LivingEntity le) || le != active.victim) return;
        e.setDamage(active.amount);
        for (DamageModifier m : new DamageModifier[]{DamageModifier.HARD_HAT, DamageModifier.BLOCKING, DamageModifier.ARMOR,
                DamageModifier.RESISTANCE, DamageModifier.MAGIC}) {
            if (e.isApplicable(m)) e.setDamage(m, 0);
        }
        if (e.isApplicable(DamageModifier.ABSORPTION)) {
            e.setDamage(DamageModifier.ABSORPTION, Mitigation.absorptionModifier(active.amount, le.getAbsorptionAmount()));
        }
    }
}
