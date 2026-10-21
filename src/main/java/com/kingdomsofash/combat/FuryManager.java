package com.kingdomsofash.combat;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.FuryFx;
import com.kingdomsofash.persist.StateStore;
import com.kingdomsofash.util.Audit;
import com.kingdomsofash.util.Keys;
import com.kingdomsofash.util.Text;
import com.kingdomsofash.weapon.WeaponLedger;
import com.kingdomsofash.weapon.WeaponService;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Last King's Fury and King's Exhaustion.
 *
 * <p>Attack speed and movement speed use <em>transient</em> attribute modifiers under this plugin's own keys
 * (never saved to the player file, so a crash cannot leave a permanent buff, and removal by key can never touch
 * another plugin's modifiers). Outgoing damage, incoming damage and fall damage are handled event-side in
 * {@link CombatListener}, which asks this manager for the player's state.</p>
 */
public final class FuryManager {
    public enum Result { OK, NOT_WIELDING, ALREADY_ACTIVE, NO_RAGE, COOLDOWN, CANCELLED }

    private static final class Active {
        long endMs;
        long startMs;
        BossBar bar;
        int counter;
    }

    private final Plugin plugin;
    private final Settings s;
    private final WeaponService weapons;
    private final RageManager rage;
    private final StateStore store;
    private final Audit audit;
    private final FuryFx fx;
    private final SpectralArmor armor;

    private final Map<UUID, Active> active = new HashMap<>();
    private final Set<UUID> exhausted = new HashSet<>();
    private BukkitTask task;
    private int tick;

    public FuryManager(Plugin plugin, Settings s, WeaponService weapons, RageManager rage, StateStore store, Audit audit,
                       FuryFx fx, SpectralArmor armor) {
        this.plugin = plugin;
        this.s = s;
        this.weapons = weapons;
        this.rage = rage;
        this.store = store;
        this.audit = audit;
        this.fx = fx;
        this.armor = armor;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    /** Plugin disable: strip every modifier and visual, no penalties. */
    public void shutdown() {
        if (task != null) task.cancel();
        for (UUID id : new HashSet<>(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) endFury(p, false, "shutdown");
        }
        for (UUID id : new HashSet<>(exhausted)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) removeMods(p);
        }
        active.clear();
        exhausted.clear();
    }

    public boolean isFury(UUID id) {
        return active.containsKey(id);
    }

    public boolean isExhausted(UUID id) {
        Long until = store.state().exhaustionUntilMs.get(id.toString());
        return until != null && until > System.currentTimeMillis();
    }

    public long cooldownLeftMs() {
        return Math.max(0, weapons.ledger().ultimateReadyAtMs - System.currentTimeMillis());
    }

    public long remainingFuryMs(UUID id) {
        Active a = active.get(id);
        return a == null ? 0 : Math.max(0, a.endMs - System.currentTimeMillis());
    }

    public Result activate(Player p) {
        if (!weapons.isWielding(p) || p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return Result.NOT_WIELDING;
        if (isFury(p.getUniqueId())) {
            p.sendActionBar(Text.mm("<dark_red>The Last King's Fury already burns in you."));
            return Result.ALREADY_ACTIVE;
        }
        long left = cooldownLeftMs();
        if (left > 0) {
            p.sendActionBar(Text.mm("<dark_gray>⌛ <gray>Last King's Fury recovers in <red>" + formatSeconds(left)));
            fxCooldown(p);
            return Result.COOLDOWN;
        }
        int have = rage.rage(p.getUniqueId());
        if (have < s.furyRequiredRage) {
            p.sendActionBar(Text.mm("<dark_red>✖ <gray>Last King's Fury needs <red>" + s.furyRequiredRage + " Rage <dark_gray>(you have " + have + ")"));
            fxCooldown(p);
            return Result.NO_RAGE;
        }
        OathbreakerAbilityEvent ev = new OathbreakerAbilityEvent(p, OathbreakerAbilityEvent.Ability.LAST_KINGS_FURY);
        Bukkit.getPluginManager().callEvent(ev);
        if (ev.isCancelled()) return Result.CANCELLED;

        // ---- committed
        rage.spendAll(p);
        long now = System.currentTimeMillis();
        WeaponLedger l = weapons.ledger();
        l.ultimateReadyAtMs = now + Math.round(s.furyCooldownSeconds * 1000);
        store.saveAsync();

        // an exhaustion from a previous cast cannot overlap (cooldown is far longer) but be safe
        exhausted.remove(p.getUniqueId());
        store.state().exhaustionUntilMs.remove(p.getUniqueId().toString());
        removeMods(p);

        Active a = new Active();
        a.startMs = now;
        a.endMs = now + Math.round(s.furyDurationSeconds * 1000);
        a.bar = BossBar.bossBar(Text.mm("<dark_red><bold>LAST KING'S FURY"), 1.0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
        p.showBossBar(a.bar);
        active.put(p.getUniqueId(), a);

        applyFuryMods(p);
        armor.show(p);
        fx.activate(p);
        audit.log("FURY", p.getName() + " activated Last King's Fury");
        return Result.OK;
    }

    private void fxCooldown(Player p) {
        p.playSound(p.getLocation(), "block.chain.hit", 0.5f, 0.7f);
    }

    static String formatSeconds(long ms) {
        long sec = (long) Math.ceil(ms / 1000.0);
        return sec >= 60 ? (sec / 60) + "m " + (sec % 60) + "s" : sec + "s";
    }

    // ------------------------------------------------------------------ modifiers

    private void setMod(Player p, Attribute attr, NamespacedKey key, double amount) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst == null) return;
        if (inst.getModifier(key) != null) inst.removeModifier(key);
        inst.addTransientModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.ADD_SCALAR));
    }

    private void clearMod(Player p, Attribute attr, NamespacedKey key) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst != null && inst.getModifier(key) != null) inst.removeModifier(key);
    }

    private void applyFuryMods(Player p) {
        setMod(p, Attribute.ATTACK_SPEED, Keys.FURY_DAMAGE_ATTACK_SPEED, s.furyAttackSpeedBonus);
        setMod(p, Attribute.MOVEMENT_SPEED, Keys.FURY_MOVE, s.furyMoveSpeedBonus);
    }

    private void applyExhaustMods(Player p) {
        setMod(p, Attribute.MOVEMENT_SPEED, Keys.EXHAUST_MOVE, -s.exhaustionMovePenalty);
    }

    /** Removes only this plugin's modifiers. Safe to call at any time (join, death, disable). */
    public void removeMods(Player p) {
        clearMod(p, Attribute.ATTACK_SPEED, Keys.FURY_DAMAGE_ATTACK_SPEED);
        clearMod(p, Attribute.MOVEMENT_SPEED, Keys.FURY_MOVE);
        clearMod(p, Attribute.MOVEMENT_SPEED, Keys.EXHAUST_MOVE);
    }

    // ------------------------------------------------------------------ lifecycle

    private void tick() {
        tick++;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Active>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Active> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            Active a = en.getValue();
            if (p == null || !p.isOnline() || p.isDead()) {
                it.remove();
                if (p != null) cleanup(p, a);
                continue;
            }
            if (now >= a.endMs) {
                it.remove();
                cleanup(p, a);
                finishToExhaustion(p);
                continue;
            }
            a.bar.progress((float) Math.max(0, Math.min(1, (a.endMs - now) / (double) (a.endMs - a.startMs))));
            a.counter++;
            if (a.counter % 2 == 0) fx.aura(p, a.counter);
        }
        Iterator<UUID> ex = exhausted.iterator();
        while (ex.hasNext()) {
            UUID id = ex.next();
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                ex.remove();
                continue;
            }
            if (!isExhausted(id)) {
                ex.remove();
                store.state().exhaustionUntilMs.remove(id.toString());
                store.saveAsync();
                removeMods(p);
                fx.exhaustionEnd(p);
                continue;
            }
            if (tick % 3 == 0) fx.exhaustionAura(p);
        }
    }

    private void cleanup(Player p, Active a) {
        if (a.bar != null) p.hideBossBar(a.bar);
        armor.hide(p);
        clearMod(p, Attribute.ATTACK_SPEED, Keys.FURY_DAMAGE_ATTACK_SPEED);
        clearMod(p, Attribute.MOVEMENT_SPEED, Keys.FURY_MOVE);
    }

    private void finishToExhaustion(Player p) {
        fx.expire(p);
        long until = System.currentTimeMillis() + Math.round(s.exhaustionSeconds * 1000);
        if (s.exhaustionSeconds > 0) {
            store.state().exhaustionUntilMs.put(p.getUniqueId().toString(), until);
            store.saveAsync();
            exhausted.add(p.getUniqueId());
            applyExhaustMods(p);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && isExhausted(p.getUniqueId())) fx.exhaustionStart(p);
            }, 14L);
        }
        audit.log("FURY", p.getName() + "'s Fury expired");
    }

    private void endFury(Player p, boolean toExhaustion, String why) {
        Active a = active.remove(p.getUniqueId());
        if (a == null) return;
        cleanup(p, a);
        if (toExhaustion) finishToExhaustion(p);
        audit.log("FURY", "Fury ended for " + p.getName() + " (" + why + ")");
    }

    public void onDeath(Player p) {
        endFury(p, false, "death");
        exhausted.remove(p.getUniqueId());
        store.state().exhaustionUntilMs.remove(p.getUniqueId().toString());
        removeMods(p);
    }

    public void onQuit(Player p) {
        Active a = active.get(p.getUniqueId());
        if (a != null) {
            long remaining = Math.max(0, a.endMs - System.currentTimeMillis());
            long until = System.currentTimeMillis() + remaining + Math.round(s.exhaustionSeconds * 1000);
            endFury(p, false, "disconnect");
            // as if they had stayed: the penalty window is queued and applied if they come back inside it
            store.state().exhaustionUntilMs.put(p.getUniqueId().toString(), until);
            store.saveAsync();
        }
        exhausted.remove(p.getUniqueId());
        armor.forget(p.getUniqueId());
        removeMods(p);
    }

    public void onJoin(Player p) {
        removeMods(p); // defensive: never trust leftovers
        Long until = store.state().exhaustionUntilMs.get(p.getUniqueId().toString());
        if (until == null) return;
        long now = System.currentTimeMillis();
        if (until <= now) {
            store.state().exhaustionUntilMs.remove(p.getUniqueId().toString());
            return;
        }
        // only the exhaustion part (not the lost Fury time) is applied on return
        long penaltyStart = until - Math.round(s.exhaustionSeconds * 1000);
        if (now >= penaltyStart) {
            exhausted.add(p.getUniqueId());
            applyExhaustMods(p);
        } else {
            long wait = Math.max(0, penaltyStart - now);
            store.state().exhaustionUntilMs.put(p.getUniqueId().toString(), now + Math.round(s.exhaustionSeconds * 1000));
            exhausted.add(p.getUniqueId());
            applyExhaustMods(p);
            if (wait > 0) store.saveAsync();
        }
    }

    public void onWorldChange(Player p) {
        if (s.furyEndOnWorldChange && isFury(p.getUniqueId())) endFury(p, true, "world change");
    }
}
