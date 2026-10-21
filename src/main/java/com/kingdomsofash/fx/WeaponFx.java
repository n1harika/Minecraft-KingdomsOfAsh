package com.kingdomsofash.fx;

import com.kingdomsofash.util.Text;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

/** Presentation of the weapon itself: held aura, swing trail, Blood Oath feedback and the dropped-blade beacon. */
public final class WeaponFx {
    private final Fx fx;

    public WeaponFx(Fx fx) {
        this.fx = fx;
    }

    public static String rageBar(int rage, int max) {
        StringBuilder sb = new StringBuilder("<dark_red>ᚱ <red>");
        for (int i = 0; i < max; i++) {
            if (i == rage) sb.append("<dark_gray>");
            sb.append(i < rage ? "◆" : "◇");
        }
        sb.append(" <gray>Rage <white>").append(rage).append("<dark_gray>/").append(max);
        return sb.toString();
    }

    private Location hand(Player p) {
        Vector dir = p.getLocation().getDirection();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        if (right.lengthSquared() > 1e-6) right.normalize();
        return p.getEyeLocation().add(dir.clone().multiply(0.55)).add(right.multiply(-0.35)).add(0, -0.55, 0);
    }

    public void show(Player p, int rage, int max) {
        p.sendActionBar(Text.mm(rageBar(rage, max)));
    }

    public void rageGain(Player p, int rage, int max, boolean maxed) {
        show(p, rage, max);
        Location h = hand(p);
        List<Player> v = fx.viewers(h);
        fx.dust(v, h, Fx.BRIGHT_RED, 1.2f, 6 + rage * 2, 0.3, true);
        fx.spawn(v, Particle.ENCHANTED_HIT, h, 4, 0.2, 0.2, 0.2, 0.2, null, true);
        fx.soundTo(p, "block.respawn_anchor.charge", 0.6f, 0.55f + rage * 0.12f);
        if (maxed) {
            fx.sound(p.getLocation(), "block.respawn_anchor.set_spawn", 0.9f, 0.6f);
            fx.sound(p.getLocation(), "entity.blaze.shoot", 0.4f, 0.5f);
            Location c = p.getLocation().add(0, 1.0, 0);
            fx.spawn(v, Particle.FLAME, c, 18, 0.4, 0.7, 0.4, 0.04, null, true);
            fx.dust(v, c, Fx.CRIMSON, 1.7f, 30, 0.7, true);
        }
    }

    public void rageLoss(Player p, int rage, int max) {
        show(p, rage, max);
        Location h = hand(p);
        List<Player> v = fx.viewers(h);
        fx.spawn(v, Particle.SMOKE, h, 4, 0.15, 0.15, 0.15, 0.01, null, false);
        fx.soundTo(p, "block.fire.extinguish", 0.35f, 0.8f);
    }

    /** Subtle ember at the hand, growing with Rage. Called every few ticks for the holder only. */
    public void aura(Player p, int rage, int tick) {
        Location h = hand(p);
        List<Player> v = fx.viewers(h, 32);
        fx.dust(v, h, rage >= 3 ? Fx.BRIGHT_RED : Fx.BLOOD, 0.9f, 1 + rage / 2, 0.12, false);
        if (rage >= 3) fx.spawn(v, Particle.SMALL_FLAME, h, 1, 0.08, 0.08, 0.08, 0.005, null, false);
        if (rage >= 5 && tick % 3 == 0) fx.spawn(v, Particle.CRIMSON_SPORE, p.getLocation().add(0, 1.0, 0), 2, 0.4, 0.6, 0.4, 0, null, false);
    }

    public void swingTrail(Player p) {
        Location base = p.getLocation();
        List<Player> v = fx.viewers(base, 32);
        double yaw = Math.toRadians(base.getYaw());
        double fx0 = -Math.sin(yaw), fz0 = Math.cos(yaw);
        double lx = fz0, lz = -fx0;
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(-70 + i * 28);
            double r = 1.9;
            Location l = base.clone().add(r * (Math.cos(a) * fx0 + Math.sin(a) * lx), 1.5 - i * 0.12, r * (Math.cos(a) * fz0 + Math.sin(a) * lz));
            fx.dust(v, l, i < 3 ? Fx.CRIMSON : Fx.BLOOD, 1.1f, 1, 0.02, false);
        }
    }

    /** Vertical crimson pillar over an unclaimed / dropped blade so the hunt is visible. */
    public void beam(Location at) {
        List<Player> v = fx.viewers(at, 64);
        if (v.isEmpty()) return;
        for (int i = 0; i < 14; i++) {
            Location l = at.clone().add(0, 0.4 + i * 1.0, 0);
            fx.dust(v, l, i % 3 == 0 ? Fx.BRIGHT_RED : Fx.CRIMSON, 1.4f, 1, 0.08, false);
        }
        fx.spawn(v, Particle.FLAME, at, 1, 0.3, 0.2, 0.3, 0.01, null, false);
        fx.spawn(v, Particle.ASH, at.clone().add(0, 1, 0), 3, 0.6, 0.6, 0.6, 0, null, false);
    }
}
