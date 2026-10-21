package com.kingdomsofash.fx;

import com.kingdomsofash.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Rupture presentation: a fast diagonal crimson cleave that traces the true hit area (a semicircle of radius =
 * range, so its width is exactly the slash width), a rune flare at the blade, a heavy layered swing sound, a
 * readable impact, a distinct bleed look (falling blood dust) and clear shield / refusal feedback.
 */
public final class RuptureFx {
    private final Fx fx;
    private int swingParity;

    public RuptureFx(Fx fx) {
        this.fx = fx;
    }

    /** Point on the slash arc. a in degrees, -90 = caster's right edge, +90 = left edge. */
    private static Location arc(Location base, double yawDeg, double radius, double aDeg) {
        double yaw = Math.toRadians(yawDeg);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double lx = fz, lz = -fx;
        double a = Math.toRadians(aDeg);
        double cx = Math.cos(a), sx = Math.sin(a);
        double y = 1.55 - (aDeg + 90.0) / 180.0 * 1.0; // high on the right, low on the left: a downward cleave
        return base.clone().add(radius * (cx * fx + sx * lx), y, radius * (cx * fz + sx * lz));
    }

    public void slash(Player p, double range, double width) {
        Location base = p.getLocation();
        float yaw = base.getYaw();
        List<Player> v = fx.viewers(base);
        double radius = Math.max(range, width / 2.0);
        boolean flip = (swingParity++ & 1) == 1; // alternate the cut direction so repeated casts feel alive

        // t=0: rune flare at the blade + heavy layered swing
        Location hand = p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.6)).add(0, -0.5, 0);
        fx.spawn(v, Particle.ENCHANTED_HIT, hand, 10, 0.25, 0.25, 0.25, 0.3, null, true);
        fx.dust(v, hand, Fx.BRIGHT_RED, 1.3f, 8, 0.25, true);
        fx.sound(base, "entity.player.attack.sweep", 1.0f, 0.55f);
        fx.sound(base, "item.trident.throw", 0.8f, 0.5f);
        fx.sound(base, "entity.wither.shoot", 0.35f, 0.7f);
        fx.sound(base, "item.mace.smash_air", 0.7f, 0.8f);

        final int frames = 5;
        fx.animate(frames, f -> {
            double a0 = -90 + 180.0 * f / frames;
            double a1 = -90 + 180.0 * (f + 1) / frames;
            for (int k = 0; k < 4; k++) {
                double a = a0 + (a1 - a0) * (k + 0.5) / 4.0;
                double aa = flip ? -a : a;
                // leading edge: bright crimson at full radius, blood-dark body, black soot trail inside
                Location edge = arc(base, yaw, radius, aa);
                fx.dust(v, edge, Fx.BRIGHT_RED, 1.5f, 1, 0.04, true);
                fx.dust(v, arc(base, yaw, radius * 0.88, aa), Fx.CRIMSON, 1.7f, 1, 0.05, true);
                fx.dust(v, arc(base, yaw, radius * 0.7, aa), Fx.BLOOD, 1.8f, 1, 0.07, true);
                fx.dust(v, arc(base, yaw, radius * 0.5, aa), Fx.CHARCOAL, 1.6f, 1, 0.08, true);
                if (k % 2 == 0) {
                    fx.spawn(v, Particle.LARGE_SMOKE, arc(base, yaw, radius * 0.6, aa), 1, 0.05, 0.05, 0.05, 0.0, null, false);
                }
            }
            if (f == 2) {
                Location mid = base.clone().add(base.getDirection().setY(0).normalize().multiply(radius * 0.7)).add(0, 1.1, 0);
                fx.spawn(v, Particle.SWEEP_ATTACK, mid, 1, 0, 0, 0, 0, null, true);
                fx.spawn(v, Particle.CRIMSON_SPORE, mid, 6, 0.5, 0.2, 0.5, 0, null, false);
            }
        });
    }

    public void hit(LivingEntity target) {
        Location c = target.getLocation().add(0, target.getHeight() * 0.6, 0);
        List<Player> v = fx.viewers(c);
        fx.dust(v, c, Fx.BRIGHT_RED, 1.4f, 16, 0.4, true);
        fx.spawn(v, Particle.BLOCK, c, 14, 0.3, 0.35, 0.3, 0.05, Material.REDSTONE_BLOCK.createBlockData(), true);
        fx.spawn(v, Particle.DAMAGE_INDICATOR, c, 4, 0.25, 0.3, 0.25, 0.1, null, true);
        fx.sound(c, "entity.player.attack.crit", 0.9f, 0.7f);
        fx.sound(c, "item.trident.hit", 0.7f, 0.6f);
        fx.sound(c, "entity.iron_golem.damage", 0.5f, 0.55f);
    }

    public void shieldBlock(LivingEntity victim, Player attacker) {
        Vector toAttacker = attacker.getLocation().toVector().subtract(victim.getLocation().toVector()).setY(0);
        if (toAttacker.lengthSquared() > 1e-6) toAttacker.normalize();
        Location c = victim.getEyeLocation().add(toAttacker.multiply(0.7)).add(0, -0.5, 0);
        List<Player> v = fx.viewers(c);
        fx.spawn(v, Particle.CRIT, c, 18, 0.3, 0.3, 0.3, 0.45, null, true);
        fx.spawn(v, Particle.ELECTRIC_SPARK, c, 10, 0.25, 0.25, 0.25, 0.3, null, true);
        fx.dust(v, c, Fx.STEEL, 1.2f, 10, 0.3, true);
        fx.sound(c, "item.shield.block", 1.0f, 0.65f);
        fx.sound(c, "block.anvil.land", 0.35f, 1.6f);
        fx.sound(c, "block.chain.break", 0.6f, 0.8f);
        if (attacker.isOnline()) Text.send(attacker, "<gray>Rupture was <white>blocked<gray>.");
    }

    public void bleedTick(LivingEntity target) {
        Location c = target.getLocation().add(0, target.getHeight() * 0.55, 0);
        List<Player> v = fx.viewers(c);
        fx.dust(v, c, Fx.BRIGHT_RED, 1.1f, 9, 0.3, true);
        fx.spawn(v, Particle.BLOCK, c, 6, 0.25, 0.3, 0.25, 0.02, Material.REDSTONE_BLOCK.createBlockData(), true);
        fx.sound(c, "block.honey_block.slide", 0.6f, 0.55f);
        fx.sound(c, "entity.player.hurt_sweet_berry_bush", 0.45f, 0.6f);
    }

    /** Ongoing indicator: dark blood droplets running off the body (distinct from fire / poison / damage). */
    public void bleedIndicator(LivingEntity target) {
        Location c = target.getLocation().add(0, target.getHeight() * 0.7, 0);
        List<Player> v = fx.viewers(c, 32);
        fx.spawn(v, Particle.FALLING_DUST, c, 3, 0.25, 0.3, 0.25, 0, Material.REDSTONE_BLOCK.createBlockData(), false);
        fx.dust(v, c, Fx.BLOOD, 0.9f, 2, 0.25, false);
    }

    public void noRage(Player p, int need, int have) {
        p.sendActionBar(Text.mm("<dark_red>✖ <gray>Rupture needs <red>" + need + " Rage <dark_gray>(you have " + have + ")"));
        fx.soundTo(p, "block.fire.extinguish", 0.5f, 1.3f);
    }

    public void cooldown(Player p, long secondsLeft) {
        p.sendActionBar(Text.mm("<dark_gray>⌛ <gray>Rupture recovers in <red>" + secondsLeft + "s"));
        fx.soundTo(p, "block.chain.hit", 0.5f, 0.8f);
    }
}
