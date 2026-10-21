package com.kingdomsofash.fx;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Presentation of the Lost Forge: ambience, ritual start, a distinct spawn look for each of the five waves,
 * wave transitions, the final-wave build-up, the reward reveal and the failure collapse. Intensity rises with the
 * wave number but never fills the screen: the rune geometry stays in the floor / around the altar and the combat
 * area itself is kept clear.
 */
public final class RitualFx {
    private final Fx fx;

    public RitualFx(Fx fx) {
        this.fx = fx;
    }

    private static Location ring(Location c, double r, double a, double y) {
        return c.clone().add(Math.cos(a) * r, y, Math.sin(a) * r);
    }

    // ------------------------------------------------------------------ ambience

    /** @param altar anvil block location; @param proximity 0..1 how close the nearest player is. */
    public void ambient(Location altar, int radius, double proximity, long tick) {
        Location top = altar.clone().add(0.5, 1.2, 0.5);
        List<Player> v = fx.viewers(top, 56);
        if (v.isEmpty()) return;
        fx.spawn(v, Particle.ASH, top.clone().add(0, 1.5, 0), 4, 5.0, 2.0, 5.0, 0, null, false);
        fx.spawn(v, Particle.SMALL_FLAME, top, 1 + (int) (proximity * 2), 0.25, 0.3, 0.25, 0.005, null, false);
        fx.dust(v, top.clone().add(0, 0.2, 0), Fx.CRIMSON, 0.9f, 1 + (int) (proximity * 4), 0.4, false);
        // slow embers rising from the outer rune ring
        double a = tick * 0.07;
        for (int k = 0; k < 3; k++) {
            double ang = a + k * Math.PI * 2 / 3;
            fx.spawn(v, Particle.DUST, ring(top, radius - 2, ang, -0.5), 1, 0.1, 0.05, 0.1, 0,
                    new Particle.DustOptions(Fx.BRIGHT_RED, 0.8f), false);
        }
        if (proximity > 0.25) {
            // the altar "breathes" as someone approaches
            for (int i = 0; i < 10; i++) {
                double ang = Math.PI * 2 * i / 10 + tick * 0.15;
                fx.dust(v, ring(top, 2.4, ang, -0.9), Fx.CRIMSON, 0.9f, 1, 0.0, false);
            }
            if (tick % 4 == 0) fx.sound(top, "block.respawn_anchor.ambient", 0.3f, 0.6f + (float) proximity * 0.4f);
        }
    }

    public void altarRefuse(Player p, Location altar) {
        Location l = altar.clone().add(0.5, 1.4, 0.5);
        List<Player> v = fx.viewers(l, 32);
        fx.spawn(v, Particle.SMOKE, l, 12, 0.3, 0.3, 0.3, 0.03, null, true);
        fx.sound(l, "block.anvil.land", 0.5f, 0.5f);
        fx.sound(l, "block.fire.extinguish", 0.6f, 0.7f);
    }

    // ------------------------------------------------------------------ start

    public void ritualStart(Location altar, int radius, int ticks) {
        Location c = altar.clone().add(0.5, 1.0, 0.5);
        World w = altar.getWorld();
        List<Player> v = fx.viewers(c, 64);
        fx.sound(c, "block.anvil.land", 1.2f, 0.5f);
        fx.sound(c, "block.beacon.activate", 1.0f, 0.5f);
        fx.sound(c, "entity.wither.spawn", 0.6f, 0.5f);
        fx.sound(c, "block.chain.place", 1.0f, 0.5f);
        fx.later(10, () -> fx.sound(c, "block.bell.use", 1.0f, 0.5f));
        fx.later(30, () -> fx.sound(c, "item.trident.thunder", 0.8f, 0.5f));
        fx.animate(Math.max(20, ticks), t -> {
            double p = Math.min(1.0, t / (double) Math.max(20, ticks));
            double rr = p * (radius - 2);
            for (int i = 0; i < 36; i++) {
                double a = Math.PI * 2 * i / 36 + t * 0.12;
                fx.dust(v, ring(c, rr, a, -0.85), Fx.BRIGHT_RED, 1.4f, 1, 0.02, true);
            }
            // pillar of crimson light rising from the anvil, thickening with time
            for (int y = 0; y < 14; y++) {
                if (y > t / 2) break;
                fx.dust(v, c.clone().add(0, y * 0.9, 0), y % 2 == 0 ? Fx.CRIMSON : Fx.BLOOD, 1.3f, 1, 0.12 + p * 0.2, false);
            }
            fx.spawn(v, Particle.FLAME, c, 2, 0.4, 0.2, 0.4, 0.03, null, false);
            if (t % 20 == 0) fx.sound(c, "block.respawn_anchor.charge", 0.7f, 0.5f + (float) p * 0.6f);
        });
        fx.later(Math.max(20, ticks) - 2, () -> {
            fx.spawn(v, Particle.SONIC_BOOM, c, 1, 0, 0, 0, 0, null, true);
            fx.sound(c, "entity.ender_dragon.growl", 0.6f, 0.6f);
        });
    }

    // ------------------------------------------------------------------ waves

    /** Distinct look per wave (1-5). Called once for every mob at its spawn position. */
    public void mobSpawn(Location l, int wave) {
        List<Player> v = fx.viewers(l, 56);
        if (v.isEmpty()) return;
        Location b = l.clone().add(0, 0.6, 0);
        switch (Math.max(1, Math.min(5, wave))) {
            case 1 -> {
                fx.spawn(v, Particle.FLAME, b, 12, 0.3, 0.5, 0.3, 0.05, null, true);
                fx.spawn(v, Particle.LARGE_SMOKE, b, 6, 0.3, 0.4, 0.3, 0.02, null, true);
                fx.sound(l, "block.fire.ambient", 0.8f, 0.7f);
            }
            case 2 -> {
                fx.spawn(v, Particle.SOUL, b, 10, 0.3, 0.6, 0.3, 0.05, null, true);
                fx.spawn(v, Particle.ASH, b, 14, 0.4, 0.6, 0.4, 0.0, null, true);
                fx.sound(l, "particle.soul_escape", 0.9f, 0.7f);
            }
            case 3 -> {
                fx.spawn(v, Particle.ELECTRIC_SPARK, b, 18, 0.35, 0.6, 0.35, 0.4, null, true);
                fx.dust(v, b, Fx.BRIGHT_RED, 1.5f, 10, 0.4, true);
                l.getWorld().strikeLightningEffect(l);
                fx.sound(l, "entity.lightning_bolt.impact", 0.6f, 0.9f);
            }
            case 4 -> {
                fx.spawn(v, Particle.CRIMSON_SPORE, b, 24, 0.4, 0.7, 0.4, 0.0, null, true);
                fx.spawn(v, Particle.DRAGON_BREATH, b, 10, 0.3, 0.5, 0.3, 0.02, null, true);
                fx.sound(l, "entity.evoker.prepare_summon", 0.9f, 0.6f);
            }
            default -> {
                fx.spawn(v, Particle.TRIAL_SPAWNER_DETECTION_OMINOUS, b, 16, 0.3, 0.6, 0.3, 0.02, null, true);
                fx.spawn(v, Particle.SOUL_FIRE_FLAME, b, 6, 0.2, 0.5, 0.2, 0.03, null, true);
                fx.dust(v, b, Fx.CHARCOAL, 1.8f, 14, 0.45, true);
                l.getWorld().strikeLightningEffect(l);
                fx.sound(l, "entity.wither.spawn", 0.35f, 1.4f);
            }
        }
    }

    public void waveStart(Location altar, int radius, int wave, int waves) {
        Location c = altar.clone().add(0.5, 1.0, 0.5);
        List<Player> v = fx.viewers(c, 64);
        float pitch = 0.55f + 0.08f * wave;
        fx.sound(c, "block.bell.use", 0.9f, pitch);
        fx.sound(c, "entity.ravager.roar", 0.4f + 0.1f * wave, 0.6f);
        fx.sound(c, "block.respawn_anchor.charge", 0.9f, pitch);
        int intensity = 14 + wave * 6;
        fx.animate(8, t -> {
            double rr = 1.0 + t * (radius - 2) / 8.0;
            for (int i = 0; i < intensity; i++) {
                double a = Math.PI * 2 * i / intensity;
                fx.dust(v, ring(c, rr, a, -0.85), wave >= waves ? Fx.BRIGHT_RED : Fx.CRIMSON, 1.4f, 1, 0.02, true);
            }
        });
        if (wave >= waves) {
            // final wave: the dome darkens and the earth shudders
            fx.spawn(v, Particle.SONIC_BOOM, c, 1, 0, 0, 0, 0, null, true);
            fx.sound(c, "entity.ender_dragon.growl", 0.7f, 0.5f);
            fx.sound(c, "entity.wither.ambient", 0.6f, 0.5f);
            fx.later(15, () -> fx.sound(c, "block.end_portal.spawn", 0.5f, 0.6f));
        }
    }

    public void lastEnemy(Location at) {
        List<Player> v = fx.viewers(at, 56);
        fx.spawn(v, Particle.END_ROD, at.clone().add(0, 1.0, 0), 8, 0.3, 0.6, 0.3, 0.02, null, true);
        fx.sound(at, "block.note_block.bell", 0.8f, 0.6f);
        fx.sound(at, "entity.experience_orb.pickup", 0.5f, 0.5f);
    }

    public void waveComplete(Location altar, int radius, int wave) {
        Location c = altar.clone().add(0.5, 1.0, 0.5);
        List<Player> v = fx.viewers(c, 64);
        fx.sound(c, "ui.toast.challenge_complete", 0.9f, 0.7f);
        fx.sound(c, "block.beacon.power_select", 0.8f, 0.6f);
        fx.sound(c, "block.chain.hit", 0.8f, 0.7f);
        fx.animate(10, t -> {
            double rr = (radius - 2) * (1.0 - t / 10.0);
            for (int i = 0; i < 30; i++) {
                double a = Math.PI * 2 * i / 30;
                fx.dust(v, ring(c, Math.max(1, rr), a, -0.85), Fx.BRIGHT_RED, 1.3f, 1, 0.02, true);
            }
        });
        fx.spawn(v, Particle.TOTEM_OF_UNDYING, c.clone().add(0, 0.5, 0), 20, 0.6, 0.8, 0.6, 0.25, null, true);
    }

    public void transitionTick(Location altar, int radius, int t) {
        Location c = altar.clone().add(0.5, 1.0, 0.5);
        List<Player> v = fx.viewers(c, 56);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + t * 0.1;
            fx.dust(v, ring(c, 2.0 + (t % 20) * 0.1, a, 0.2), Fx.CRIMSON, 1.0f, 1, 0.0, false);
        }
        fx.spawn(v, Particle.ASH, c.clone().add(0, 2, 0), 6, 4.0, 1.0, 4.0, 0, null, false);
    }

    // ------------------------------------------------------------------ reward

    /** The climax. The caller spawns the item after {@code ticks}; this builds the anticipation. */
    public void rewardSequence(Location altar, int radius, int ticks) {
        Location c = altar.clone().add(0.5, 1.0, 0.5);
        List<Player> v = fx.viewers(c, 72);
        fx.sound(c, "block.beacon.activate", 1.0f, 0.5f);
        fx.sound(c, "entity.wither.death", 0.5f, 0.5f);
        fx.animate(ticks, t -> {
            double p = t / (double) ticks;
            // two counter-rotating helixes converging on the anvil
            for (int h = 0; h < 2; h++) {
                double dir = h == 0 ? 1 : -1;
                double a = dir * t * 0.35 + h * Math.PI;
                double rr = (1.0 - p) * (radius - 4) + 0.6;
                double y = p * 4.0;
                fx.dust(v, ring(c, rr, a, y - 0.2), Fx.BRIGHT_RED, 1.6f, 1, 0.02, true);
                fx.dust(v, ring(c, rr * 0.8, a + 0.2, y), Fx.CRIMSON, 1.4f, 1, 0.02, true);
            }
            for (int y = 0; y < 16; y++) {
                fx.dust(v, c.clone().add(0, y * 0.9, 0), y % 2 == 0 ? Fx.BRIGHT_RED : Fx.BLOOD, 1.2f + (float) p, 1, 0.08 + p * 0.15, false);
            }
            fx.spawn(v, Particle.ENCHANT, c.clone().add(0, 1, 0), 6 + (int) (p * 20), 2.5, 1.2, 2.5, 0.8, null, false);
            fx.spawn(v, Particle.SMALL_FLAME, c, 2, 0.3, 0.3, 0.3, 0.02, null, false);
            if (t % 10 == 0) fx.sound(c, "block.respawn_anchor.charge", 0.9f, 0.5f + (float) p * 0.9f);
            if (t % 20 == 0) fx.sound(c, "block.note_block.bass", 0.9f, 0.5f + (float) p * 0.5f);
            if (t == ticks - 12) fx.sound(c, "entity.ender_dragon.growl", 0.9f, 0.5f);
        });
    }

    public void rewardReveal(Location at) {
        List<Player> v = fx.viewers(at, 80);
        fx.sound(at, "entity.lightning_bolt.thunder", 1.2f, 0.6f);
        fx.sound(at, "block.bell.resonate", 1.2f, 0.5f);
        fx.sound(at, "item.trident.return", 1.0f, 0.5f);
        fx.sound(at, "ui.toast.challenge_complete", 1.0f, 0.5f);
        at.getWorld().strikeLightningEffect(at);
        fx.spawn(v, Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0, null, true);
        fx.spawn(v, Particle.SONIC_BOOM, at, 1, 0, 0, 0, 0, null, true);
        fx.spawn(v, Particle.TOTEM_OF_UNDYING, at, 60, 1.2, 1.5, 1.2, 0.5, null, true);
        fx.dust(v, at, Fx.BRIGHT_RED, 2.0f, 80, 1.8, true);
        fx.spawn(v, Particle.BLOCK, at, 40, 1.0, 0.5, 1.0, 0.1, Material.REDSTONE_BLOCK.createBlockData(), true);
    }

    // ------------------------------------------------------------------ failure

    public void fail(Location altar, int radius) {
        Location c = altar.clone().add(0.5, 1.0, 0.5);
        List<Player> v = fx.viewers(c, 64);
        fx.sound(c, "entity.wither.death", 0.8f, 0.5f);
        fx.sound(c, "block.beacon.deactivate", 1.0f, 0.5f);
        fx.sound(c, "block.chain.break", 1.0f, 0.5f);
        fx.sound(c, "block.anvil.destroy", 0.8f, 0.6f);
        fx.animate(14, t -> {
            double rr = 1 + t * (radius - 2) / 14.0;
            for (int i = 0; i < 30; i++) {
                double a = Math.PI * 2 * i / 30;
                fx.spawn(v, Particle.DUST, ring(c, rr, a, -0.85), 1, 0.05, 0.02, 0.05, 0,
                        new Particle.DustOptions(Fx.CHARCOAL, 1.8f), true);
            }
        });
        fx.spawn(v, Particle.LARGE_SMOKE, c, 40, 2.0, 1.0, 2.0, 0.02, null, true);
        fx.spawn(v, Particle.SQUID_INK, c, 30, 1.5, 0.8, 1.5, 0.05, null, true);
    }
}
