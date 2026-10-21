package com.kingdomsofash.fx;

import com.kingdomsofash.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.logging.Level;

/**
 * Low-level visual / audio toolbox shared by every effect class.
 *
 * <p>Performance model: every particle call is delivered only to players inside {@code effect-distance}
 * (never the whole server), counts are scaled by {@code particle-density} and an automatic TPS throttle, and
 * "decorative" spawns draw from a per-tick budget. "Essential" spawns (attack direction, impact, bleed ticks,
 * shield block) bypass the budget so combat stays readable even when ambience is being shed.</p>
 */
public final class Fx {
    /** Colour language: crimson, blood, charcoal, steel, restrained ember. */
    public static final Color CRIMSON = Color.fromRGB(176, 20, 31);
    public static final Color BLOOD = Color.fromRGB(112, 4, 10);
    public static final Color BRIGHT_RED = Color.fromRGB(222, 38, 38);
    public static final Color CHARCOAL = Color.fromRGB(26, 22, 24);
    public static final Color STEEL = Color.fromRGB(96, 100, 110);
    public static final Color EMBER = Color.fromRGB(255, 122, 36);
    public static final Color ASH_GRAY = Color.fromRGB(70, 66, 68);

    private final Plugin plugin;
    private Settings s;
    private double throttle = 1.0;
    private int budgetTick = -1;
    private int budgetUsed;
    private BukkitTask throttleTask;

    public Fx(Plugin plugin, Settings s) {
        this.plugin = plugin;
        this.s = s;
    }

    public void settings(Settings s) {
        this.s = s;
    }

    public void start() {
        throttleTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!s.tpsThrottle) {
                    throttle = 1.0;
                    return;
                }
                double tps = Math.min(20.0, Bukkit.getTPS()[0]);
                if (tps >= s.tpsBelow) throttle = 1.0;
                else if (tps >= s.tpsBelow - 3) throttle = 0.5;
                else throttle = 0.25;
            }
        }.runTaskTimer(plugin, 40L, 40L);
    }

    public void stop() {
        if (throttleTask != null) throttleTask.cancel();
    }

    public Plugin plugin() {
        return plugin;
    }

    public double throttle() {
        return throttle;
    }

    // ------------------------------------------------------------------ audience

    public List<Player> viewers(Location center) {
        return viewers(center, s.effectDistance);
    }

    public List<Player> viewers(Location center, double radius) {
        List<Player> out = new ArrayList<>();
        World w = center.getWorld();
        if (w == null) return out;
        double r2 = radius * radius;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(center) <= r2) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ particles

    /** Density-scaled count (never below 1 for a positive request). */
    public int n(int base) {
        if (base <= 0) return 0;
        return Math.max(1, (int) Math.round(base * s.particleDensity * throttle));
    }

    public boolean budget(int cost) {
        int tick = Bukkit.getCurrentTick();
        if (tick != budgetTick) {
            budgetTick = tick;
            budgetUsed = 0;
        }
        if (budgetUsed + cost > s.decorativeBudget) return false;
        budgetUsed += cost;
        return true;
    }

    public void spawn(List<Player> to, Particle p, Location l, int count, double dx, double dy, double dz, double extra, Object data, boolean essential) {
        int c = n(count);
        if (!essential && !budget(c)) return;
        for (Player v : to) {
            v.spawnParticle(p, l.getX(), l.getY(), l.getZ(), c, dx, dy, dz, extra, data);
        }
    }

    public void spawn(List<Player> to, Particle p, Location l, int count, double spread, boolean essential) {
        spawn(to, p, l, count, spread, spread, spread, 0, null, essential);
    }

    public void dust(List<Player> to, Location l, Color c, float size, int count, double spread, boolean essential) {
        spawn(to, Particle.DUST, l, count, spread, spread, spread, 0, new Particle.DustOptions(c, size), essential);
    }

    public void dustTransition(List<Player> to, Location l, Color from, Color toC, float size, int count, double spread, boolean essential) {
        spawn(to, Particle.DUST_COLOR_TRANSITION, l, count, spread, spread, spread, 0,
                new Particle.DustTransition(from, toC, size), essential);
    }

    // ------------------------------------------------------------------ sound

    public void sound(Location l, String key, float volume, float pitch) {
        World w = l.getWorld();
        if (w == null || s.soundVolume <= 0) return;
        w.playSound(l, key, SoundCategory.PLAYERS, (float) (volume * s.soundVolume), pitch);
    }

    public void soundTo(Player p, String key, float volume, float pitch) {
        if (s.soundVolume <= 0) return;
        p.playSound(p.getLocation(), key, SoundCategory.PLAYERS, (float) (volume * s.soundVolume), pitch);
    }

    /** Sound heard by everyone on the server at full strength (used by the global announcement). */
    public void soundGlobal(String key, float volume, float pitch) {
        if (s.soundVolume <= 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), key, SoundCategory.MASTER, (float) (volume * s.soundVolume), pitch);
        }
    }

    // ------------------------------------------------------------------ animation

    /** Runs {@code frame(0..ticks-1)} once per tick; self-cancelling and exception-safe. */
    public BukkitTask animate(int ticks, IntConsumer frame) {
        return new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                try {
                    if (t >= ticks) {
                        cancel();
                        return;
                    }
                    frame.accept(t++);
                } catch (Throwable e) {
                    plugin.getLogger().log(Level.WARNING, "effect animation failed", e);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    public void later(long ticks, Runnable r) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                r.run();
            } catch (Throwable e) {
                plugin.getLogger().log(Level.WARNING, "scheduled effect failed", e);
            }
        }, ticks);
    }
}
