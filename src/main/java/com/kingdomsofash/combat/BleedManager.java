package com.kingdomsofash.combat;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.RuptureFx;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * All bleeds live in one map driven by ONE repeating task (no per-target scheduler). Re-applying refreshes
 * the duration and never stacks. Bleed on a disconnected player is frozen and resumes on re-join, so logging
 * out cannot be used to shed it. Bleed is intentionally transient: it does not survive a server restart.
 */
public final class BleedManager {
    private static final class Bleed {
        UUID attacker;
        int endTick;
        int nextTick;
        int remainingWhenFrozen = -1;
        int nextOffsetWhenFrozen;
    }

    private final Plugin plugin;
    private final Settings s;
    private final RuptureFx fx;
    private final Map<UUID, Bleed> active = new HashMap<>();
    private final Map<UUID, Bleed> frozen = new HashMap<>();
    private BukkitTask task;

    public BleedManager(Plugin plugin, Settings s, RuptureFx fx) {
        this.plugin = plugin;
        this.s = s;
        this.fx = fx;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        active.clear();
        frozen.clear();
    }

    public int count() {
        return active.size();
    }

    public boolean isBleeding(UUID id) {
        return active.containsKey(id);
    }

    /** Applies or refreshes bleed. The next tick keeps its phase when already bleeding. */
    public void apply(LivingEntity target, Player attacker) {
        int now = Bukkit.getCurrentTick();
        int interval = Math.max(1, (int) Math.round(s.ruptureBleedIntervalSeconds * 20));
        int duration = (int) Math.round(s.ruptureBleedDurationSeconds * 20);
        Bleed b = active.get(target.getUniqueId());
        if (b == null) {
            b = new Bleed();
            b.nextTick = now + interval;
            active.put(target.getUniqueId(), b);
        } else if (b.nextTick > now + interval) {
            b.nextTick = now + interval;
        }
        b.attacker = attacker.getUniqueId();
        b.endTick = now + duration; // refresh, never stack
    }

    public void remove(UUID id) {
        active.remove(id);
        frozen.remove(id);
    }

    public void onQuit(Player p) {
        Bleed b = active.remove(p.getUniqueId());
        if (b == null) return;
        int now = Bukkit.getCurrentTick();
        b.remainingWhenFrozen = Math.max(0, b.endTick - now);
        b.nextOffsetWhenFrozen = Math.max(1, b.nextTick - now);
        frozen.put(p.getUniqueId(), b);
    }

    public void onJoin(Player p) {
        Bleed b = frozen.remove(p.getUniqueId());
        if (b == null || b.remainingWhenFrozen <= 0) return;
        int now = Bukkit.getCurrentTick();
        b.endTick = now + b.remainingWhenFrozen;
        b.nextTick = now + b.nextOffsetWhenFrozen;
        active.put(p.getUniqueId(), b);
    }

    private void tick() {
        if (active.isEmpty()) return;
        int now = Bukkit.getCurrentTick();
        int interval = Math.max(1, (int) Math.round(s.ruptureBleedIntervalSeconds * 20));
        Iterator<Map.Entry<UUID, Bleed>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Bleed> en = it.next();
            Bleed b = en.getValue();
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid()) {
                it.remove();
                continue;
            }
            if (now > b.endTick) {
                it.remove();
                continue;
            }
            if (now >= b.nextTick) {
                b.nextTick += interval;
                Player attacker = Bukkit.getPlayer(b.attacker);
                fx.bleedTick(le);
                boolean hit = TrueDamage.apply(le, attacker, s.ruptureBleedHearts * 2.0, "BLEED");
                if (!hit && !le.isDead()) {
                    // cancelled by protection (safe zone etc.): bleed ends instead of retrying forever
                    it.remove();
                    continue;
                }
            }
            if (now % 8 == 0) fx.bleedIndicator(le);
        }
    }
}
