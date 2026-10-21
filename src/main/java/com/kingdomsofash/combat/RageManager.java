package com.kingdomsofash.combat;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.WeaponFx;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Bukkit-facing owner of {@link RageModel} instances (only holders have one) and their feedback. */
public final class RageManager {
    private final Plugin plugin;
    private final Settings s;
    private final WeaponFx fx;
    private final Map<UUID, RageModel> models = new HashMap<>();
    /** attacker -> (victim -> tick) duplicate-event guard: one valid hit per (attacker, victim, tick). */
    private final Map<UUID, Map<UUID, Integer>> lastHitTick = new HashMap<>();
    private BukkitTask task;
    private int auraCounter;

    public RageManager(Plugin plugin, Settings s, WeaponFx fx) {
        this.plugin = plugin;
        this.s = s;
        this.fx = fx;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    public void stop() {
        if (task != null) task.cancel();
        models.clear();
        lastHitTick.clear();
    }

    private RageModel create() {
        return new RageModel(s.hitsPerRage, s.maxRage, Math.round(s.decayDelaySeconds * 1000), Math.round(s.decayIntervalSeconds * 1000));
    }

    public RageModel model(UUID id) {
        return models.computeIfAbsent(id, k -> create());
    }

    public int rage(UUID id) {
        RageModel m = models.get(id);
        return m == null ? 0 : m.rage();
    }

    /** @return false if this (attacker, victim) pair was already counted this tick. */
    public boolean registerHit(Player attacker, UUID victim) {
        int now = Bukkit.getCurrentTick();
        Map<UUID, Integer> per = lastHitTick.computeIfAbsent(attacker.getUniqueId(), k -> new HashMap<>());
        Integer prev = per.put(victim, now);
        if (prev != null && prev == now) return false;
        if (per.size() > 32) per.values().removeIf(t -> now - t > 40);
        RageModel m = model(attacker.getUniqueId());
        RageModel.Change c = m.hit(System.currentTimeMillis());
        if (c == RageModel.Change.GAINED || c == RageModel.Change.GAINED_MAX) {
            fx.rageGain(attacker, m.rage(), m.max(), c == RageModel.Change.GAINED_MAX);
        }
        return true;
    }

    public boolean spend(Player p, int amount) {
        RageModel m = model(p.getUniqueId());
        boolean ok = m.spend(amount);
        if (ok && amount > 0) fx.show(p, m.rage(), m.max());
        return ok;
    }

    public void spendAll(Player p) {
        RageModel m = model(p.getUniqueId());
        m.spendAll();
        fx.show(p, 0, m.max());
    }

    /** Drops Rage of anyone who is not the (single) current holder. */
    public void retainOnly(UUID holder) {
        models.keySet().removeIf(id -> !id.equals(holder));
        lastHitTick.keySet().removeIf(id -> !id.equals(holder));
    }

    public void clear(UUID id) {
        models.remove(id);
        lastHitTick.remove(id);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        auraCounter++;
        Iterator<Map.Entry<UUID, RageModel>> it = models.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, RageModel> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            RageModel m = en.getValue();
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            RageModel.Change c = m.tick(now);
            if (c == RageModel.Change.LOST) fx.rageLoss(p, m.rage(), m.max());
            if (s.weaponAura && auraCounter % 3 == 0 && m.rage() > 0) fx.aura(p, m.rage(), auraCounter);
        }
    }
}
