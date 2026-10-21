package com.kingdomsofash.fx;

import com.kingdomsofash.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Last King's Fury presentation: a short readable awakening (pulse, shockwave, rune circle, smoke and embers,
 * layered ominous audio), a subtle continuous aura, a collapse on expiry and a visibly darker, grey/ash
 * treatment for King's Exhaustion. The spectral armor itself is handled by {@code SpectralArmor}.
 */
public final class FuryFx {
    private final Fx fx;

    public FuryFx(Fx fx) {
        this.fx = fx;
    }

    private static Location ringPoint(Location c, double r, double angleRad, double y) {
        return c.clone().add(Math.cos(angleRad) * r, y, Math.sin(angleRad) * r);
    }

    public void activate(Player p) {
        Location c = p.getLocation();
        List<Player> v = fx.viewers(c);

        // layered audio: bell + armor forging + a king's roar + rumbling thunder
        fx.sound(c, "block.bell.use", 1.0f, 0.5f);
        fx.sound(c, "item.armor.equip_netherite", 1.0f, 0.55f);
        fx.sound(c, "entity.ravager.roar", 0.7f, 0.55f);
        fx.sound(c, "item.trident.thunder", 0.6f, 0.6f);
        fx.sound(c, "block.chain.place", 0.8f, 0.5f);
        fx.later(6, () -> fx.sound(c, "entity.wither.ambient", 0.7f, 0.6f));
        fx.later(10, () -> fx.sound(c, "block.beacon.activate", 0.6f, 0.5f));

        Location chest = c.clone().add(0, 1.0, 0);
        fx.spawn(v, Particle.SONIC_BOOM, chest, 1, 0, 0, 0, 0, null, true);
        fx.dust(v, chest, Fx.BRIGHT_RED, 2.0f, 30, 0.5, true);
        fx.spawn(v, Particle.DUST_PLUME, chest, 18, 0.5, 0.7, 0.5, 0.02, null, true);

        // shockwave ring (10 ticks): crimson edge, black smoke trailing behind it
        fx.animate(10, t -> {
            double r = 0.8 + t * 0.65;
            int pts = 26 + t * 2;
            for (int i = 0; i < pts; i++) {
                double a = Math.PI * 2 * i / pts;
                fx.dust(v, ringPoint(c, r, a, 0.15), Fx.BRIGHT_RED, 1.6f, 1, 0.02, true);
                if (i % 2 == 0) fx.dust(v, ringPoint(c, r * 0.8, a, 0.25), Fx.CHARCOAL, 1.8f, 1, 0.05, true);
                if (i % 5 == 0) fx.spawn(v, Particle.LARGE_SMOKE, ringPoint(c, r * 0.7, a, 0.3), 1, 0.05, 0.1, 0.05, 0.01, null, false);
            }
            // rotating rune circle on the floor
            double spin = t * 0.5;
            for (int ring = 0; ring < 2; ring++) {
                double rr = 1.3 + ring * 0.8;
                int glyphs = 8 + ring * 4;
                for (int g = 0; g < glyphs; g++) {
                    double a = spin * (ring == 0 ? 1 : -1) + Math.PI * 2 * g / glyphs;
                    fx.dust(v, ringPoint(c, rr, a, 0.08), Fx.CRIMSON, 1.0f, 1, 0.0, false);
                    if (g % 2 == 0) fx.dust(v, ringPoint(c, rr + 0.18, a, 0.08), Fx.BLOOD, 0.8f, 1, 0.0, false);
                }
            }
            // embers rising and the armor "forming" as dark motes around the body
            fx.spawn(v, Particle.FLAME, c.clone().add(0, 0.2, 0), 3, 0.5, 0.1, 0.5, 0.06, null, false);
            fx.dust(v, c.clone().add(0, 1.0, 0), Fx.CHARCOAL, 1.4f, 5, 0.45, false);
            fx.spawn(v, Particle.BLOCK, c.clone().add(0, 1.0, 0), 3, 0.4, 0.8, 0.4, 0.0, Material.BLACKSTONE.createBlockData(), false);
        });
        p.sendActionBar(Text.mm("<dark_red><bold>LAST KING'S FURY"));
    }

    /** Continuous but light aura. {@code tick} is a running counter from the manager. */
    public void aura(Player p, int tick) {
        Location c = p.getLocation();
        List<Player> v = fx.viewers(c, 40);
        fx.dust(v, c.clone().add(0, 1.0, 0), Fx.CHARCOAL, 1.2f, 2, 0.4, false);
        fx.spawn(v, Particle.DUST, c.clone().add(0, 0.2, 0), 1, 0.35, 0.05, 0.35, 0, new Particle.DustOptions(Fx.BRIGHT_RED, 1.0f), false);
        if (tick % 2 == 0) fx.spawn(v, Particle.SMALL_FLAME, c.clone().add(0, 1.6, 0), 1, 0.3, 0.2, 0.3, 0.01, null, false);
        if (tick % 3 == 0) {
            double spin = tick * 0.35;
            for (int g = 0; g < 6; g++) {
                double a = spin + Math.PI * 2 * g / 6;
                fx.dust(v, ringPoint(c, 1.15, a, 0.06), Fx.CRIMSON, 0.9f, 1, 0.0, false);
            }
        }
    }

    public void expire(Player p) {
        Location c = p.getLocation();
        List<Player> v = fx.viewers(c);
        fx.sound(c, "block.respawn_anchor.deplete", 0.9f, 0.6f);
        fx.sound(c, "entity.iron_golem.death", 0.6f, 0.55f);
        fx.sound(c, "block.chain.break", 0.8f, 0.5f);
        fx.sound(c, "item.armor.equip_chain", 0.7f, 0.5f);
        fx.animate(6, t -> {
            double r = 3.2 - t * 0.45;
            for (int i = 0; i < 20; i++) {
                double a = Math.PI * 2 * i / 20;
                fx.dust(v, ringPoint(c, r, a, 0.2 + t * 0.12), t % 2 == 0 ? Fx.CHARCOAL : Fx.CRIMSON, 1.4f, 1, 0.03, true);
            }
        });
        Location body = c.clone().add(0, 1.0, 0);
        fx.spawn(v, Particle.BLOCK, body, 34, 0.4, 0.8, 0.4, 0.1, Material.BLACKSTONE.createBlockData(), true);
        fx.spawn(v, Particle.SQUID_INK, body, 10, 0.35, 0.6, 0.35, 0.05, null, true);
        p.sendActionBar(Text.mm("<dark_gray>The Last King's Fury fades..."));
    }

    public void exhaustionStart(Player p) {
        fx.sound(p.getLocation(), "entity.wither.hurt", 0.4f, 0.5f);
        fx.sound(p.getLocation(), "block.soul_sand.break", 0.7f, 0.5f);
        p.sendActionBar(Text.mm("<dark_gray>☠ <gray>King's Exhaustion <dark_gray>- weakened and slowed"));
    }

    /** Dark, desaturated and heavy: falling ash and ink, no crimson at all. */
    public void exhaustionAura(Player p) {
        Location c = p.getLocation();
        List<Player> v = fx.viewers(c, 32);
        fx.spawn(v, Particle.ASH, c.clone().add(0, 1.4, 0), 6, 0.4, 0.5, 0.4, 0, null, false);
        fx.spawn(v, Particle.SQUID_INK, c.clone().add(0, 1.0, 0), 1, 0.3, 0.4, 0.3, 0.0, null, false);
        fx.dust(v, c.clone().add(0, 0.8, 0), Fx.ASH_GRAY, 1.2f, 2, 0.35, false);
    }

    public void exhaustionEnd(Player p) {
        fx.soundTo(p, "block.beacon.deactivate", 0.5f, 0.9f);
        p.sendActionBar(Text.mm("<gray>You steady yourself."));
    }
}
