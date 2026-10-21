package com.kingdomsofash.ritual;

import com.kingdomsofash.config.Settings;
import com.kingdomsofash.forge.ForgeService;
import com.kingdomsofash.fx.Fx;
import com.kingdomsofash.fx.RitualFx;
import com.kingdomsofash.ore.BloodironService;
import com.kingdomsofash.persist.ForgeState;
import com.kingdomsofash.persist.RitualData;
import com.kingdomsofash.persist.RitualData.Phase;
import com.kingdomsofash.persist.StateStore;
import com.kingdomsofash.util.Audit;
import com.kingdomsofash.util.Keys;
import com.kingdomsofash.util.Text;
import com.kingdomsofash.weapon.WeaponLedger;
import com.kingdomsofash.weapon.WeaponService;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The ritual state machine: IDLE -> STARTING -> (WAVE_ACTIVE <-> WAVE_TRANSITION) x5 -> REWARD_SEQUENCE ->
 * REWARD_PENDING -> COMPLETED, with FAILED (retry cooldown) and INTERRUPTED (server restarted mid-trial) as the
 * other terminal-ish states. Every transition is validated against the persisted phase and encounter id, so
 * duplicated events or stale scheduled tasks cannot spawn a wave twice or skip one.
 */
public final class RitualManager {
    public enum StartResult { OK, RESUMED, NOT_READY, WEAPON_EXISTS, IN_PROGRESS, COOLDOWN, NO_MATERIAL, PERSIST_FAILED, DISABLED }

    private static final class MobTrack {
        int type;
        Location lastPos;
        long lastMoveMs;
    }

    private final Plugin plugin;
    private final Settings s;
    private final StateStore store;
    private final Audit audit;
    private final ForgeService forge;
    private final WeaponService weapons;
    private final BloodironService bloodiron;
    private final RitualFx rfx;
    private final Fx fx;
    private final Random rnd = new Random();

    private final Map<UUID, MobTrack> mobs = new LinkedHashMap<>();
    private int spawnedThisWave;
    private int killedThisWave;
    private int[] waveCounts = new int[WaveComposer.TYPES];
    private int[] waveSpawnedByType = new int[WaveComposer.TYPES];
    private BukkitTask pollTask;
    private BukkitTask spawnTask;
    private final List<BukkitTask> scheduled = new ArrayList<>();
    private BossBar bar;
    private final Set<UUID> barViewers = new HashSet<>();
    private boolean checkPending;
    private int pollCounter;
    private boolean glowedLast;

    public RitualManager(Plugin plugin, Settings s, StateStore store, Audit audit, ForgeService forge, WeaponService weapons,
                         BloodironService bloodiron, RitualFx rfx, Fx fx) {
        this.plugin = plugin;
        this.s = s;
        this.store = store;
        this.audit = audit;
        this.forge = forge;
        this.weapons = weapons;
        this.bloodiron = bloodiron;
        this.rfx = rfx;
        this.fx = fx;
    }

    public RitualData data() {
        return store.state().ritual;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    public Phase phase() {
        return data().phase;
    }

    public boolean active() {
        return data().inProgress();
    }

    public String encounterId() {
        return data().encounterId;
    }

    public int aliveMobs() {
        return mobs.size();
    }

    public Map<UUID, MobTrack> trackedMobs() {
        return mobs;
    }

    // ------------------------------------------------------------------ boot / shutdown

    public void bootstrap() {
        RitualData d = data();
        WeaponLedger l = weapons.ledger();
        if (!forge.state().present()) return;
        World w = forge.world();
        if (w != null && !w.getPVP()) {
            plugin.getLogger().warning("PvP is DISABLED in world '" + w.getName() + "'. The Lost Forge needs PvP enabled to work as designed.");
        }
        switch (d.phase) {
            case STARTING, WAVE_ACTIVE, WAVE_TRANSITION -> {
                audit.log("RITUAL", "server restarted during " + d.phase + " (wave " + d.wave + ", encounter " + d.encounterId + ")");
                purgeArenaMobs();
                if (s.onRestart.equals("FAIL")) {
                    d.phase = Phase.WAVE_ACTIVE; // so fail() accepts it
                    fail("the server restarted during the trial");
                } else {
                    d.phase = Phase.INTERRUPTED;
                    d.lastTransitionMs = now();
                    store.saveNow();
                    runesOff(true);
                }
            }
            case REWARD_SEQUENCE -> {
                audit.log("RITUAL", "server restarted during the reward sequence; completing it now");
                d.phase = Phase.REWARD_PENDING;
                Location altar = forge.altarLocation();
                if (altar != null && forge.ready()) {
                    forge.withChunkLoaded(altar, () -> {
                        weapons.spawnReward(altar.clone().add(0.5, 1.5, 0.5));
                        store.saveNow();
                    });
                }
            }
            case INTERRUPTED, IDLE, FAILED, REWARD_PENDING, COMPLETED -> {
                // consistency: a weapon already exists (e.g. admin gave it) -> the forge is silent
                if ((l.phase == WeaponLedger.Phase.HELD || l.phase == WeaponLedger.Phase.DROPPED)
                        && d.phase != Phase.COMPLETED) {
                    d.phase = Phase.COMPLETED;
                    store.saveAsync();
                }
                if (d.phase != Phase.REWARD_PENDING) runesOff(true);
            }
        }
        if (d.phase == Phase.REWARD_PENDING && l.phase != WeaponLedger.Phase.REWARD_PENDING) {
            d.phase = (l.phase == WeaponLedger.Phase.NONE) ? Phase.IDLE : Phase.COMPLETED;
            store.saveAsync();
        }
        startPoll();
    }

    private void runesOff(boolean quick) {
        if (forge.ready()) forge.setRunes(false, quick ? 4 : 60, null);
    }

    public void shutdown() {
        cancelTasks();
        if (bar != null) {
            for (UUID id : barViewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(bar);
            }
            barViewers.clear();
        }
        if (pollTask != null) pollTask.cancel();
        List<UUID> ids = new ArrayList<>(mobs.keySet());
        mobs.clear(); // before removing, so removal events are not mistaken for lost mobs
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        forge.releaseTickets();
    }

    private void cancelTasks() {
        if (spawnTask != null) spawnTask.cancel();
        spawnTask = null;
        for (BukkitTask t : scheduled) t.cancel();
        scheduled.clear();
    }

    private void startPoll() {
        if (pollTask != null) pollTask.cancel();
        pollTask = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, s.alivePollTicks, s.alivePollTicks);
    }

    private BukkitTask later(String encounter, long ticks, Runnable r) {
        BukkitTask[] holder = new BukkitTask[1];
        holder[0] = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduled.remove(holder[0]);
            if (encounter != null && !encounter.equals(data().encounterId)) return; // stale task from a dead encounter
            try {
                r.run();
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "ritual step failed", e);
            }
        }, ticks);
        scheduled.add(holder[0]);
        return holder[0];
    }

    // ------------------------------------------------------------------ starting

    public int retryRemainingSeconds() {
        RitualData d = data();
        if (d.phase != Phase.FAILED) return 0;
        return (int) Math.max(0, Math.ceil((d.retryAvailableAtMs - now()) / 1000.0));
    }

    public StartResult tryStart(Player p) {
        RitualData d = data();
        WeaponLedger l = weapons.ledger();
        if (!forge.ready()) return StartResult.NOT_READY;
        if (l.exists() || d.phase == Phase.COMPLETED || d.phase == Phase.REWARD_PENDING || d.phase == Phase.REWARD_SEQUENCE) {
            return StartResult.WEAPON_EXISTS;
        }
        if (d.inProgress()) return StartResult.IN_PROGRESS;
        if (d.phase == Phase.FAILED && now() < d.retryAvailableAtMs) return StartResult.COOLDOWN;

        if (d.phase == Phase.INTERRUPTED) {
            // free resume of the interrupted wave: the material was paid for already
            d.encounterId = UUID.randomUUID().toString();
            d.phase = Phase.STARTING;
            d.wave = Math.max(0, d.wave - 1);
            d.lastTransitionMs = now();
            if (!store.saveNow()) {
                d.phase = Phase.INTERRUPTED;
                return StartResult.PERSIST_FAILED;
            }
            audit.log("RITUAL", p.getName() + " resumed the interrupted trial at wave " + (d.wave + 1));
            begin(p, true);
            return StartResult.RESUMED;
        }

        int need = s.bloodironRequired;
        if (bloodiron.countAuthentic(p) < need) return StartResult.NO_MATERIAL;

        // ---- transaction: remove -> save player -> durable commit; roll back on commit failure
        if (!bloodiron.take(p, need)) return StartResult.NO_MATERIAL;
        p.saveData();
        Phase prevPhase = d.phase;
        String prevEnc = d.encounterId;
        d.phase = Phase.STARTING;
        d.encounterId = UUID.randomUUID().toString();
        d.initiatorId = p.getUniqueId().toString();
        d.initiatorName = p.getName();
        d.startedAtMs = now();
        d.lastTransitionMs = now();
        d.wave = 0;
        d.attempts++;
        d.lastFailReason = null;
        if (!store.saveNow()) {
            d.phase = prevPhase;
            d.encounterId = prevEnc;
            d.attempts--;
            bloodiron.giveBack(p, need);
            p.saveData();
            return StartResult.PERSIST_FAILED;
        }
        audit.log("RITUAL", p.getName() + " (" + p.getUniqueId() + ") consumed " + need + " Bloodiron and began attempt #" + d.attempts
                + " encounter " + d.encounterId);
        begin(p, false);
        return StartResult.OK;
    }

    private void begin(Player initiator, boolean resume) {
        RitualData d = data();
        String enc = d.encounterId;
        Location altar = forge.altarLocation();
        forge.ticketArena();
        killedThisWave = 0;
        spawnedThisWave = 0;
        glowedLast = false;
        mobs.clear();
        forge.setRunes(true, 40, null);
        rfx.ritualStart(altar, forge.state().radius, s.introSeconds * 20);
        String name = Text.esc(initiator.getName());
        broadcastNear(Text.mm("<dark_red>☠ <red>" + name + "<gray> has awakened the <dark_red>Lost Forge<gray>. The trial of ash begins."));
        titleArena(Title.title(Text.mm("<dark_red><bold>THE TRIAL OF ASH"), Text.mm("<gray>" + (resume ? "The forge stirs again..." : "The runes ignite..."))));
        later(enc, Math.max(20, s.introSeconds * 20L), () -> startWave(d.wave + 1));
    }

    // ------------------------------------------------------------------ waves

    private void startWave(int n) {
        RitualData d = data();
        if (n != d.wave + 1 || n > s.waves) return;
        if (d.phase != Phase.STARTING && d.phase != Phase.WAVE_TRANSITION) return; // duplicate / stale call
        String enc = d.encounterId;
        d.phase = Phase.WAVE_ACTIVE;
        d.wave = n;
        d.lastTransitionMs = now();
        store.saveAsync();

        double[] weights = new double[WaveComposer.TYPES];
        for (int t = 0; t < WaveComposer.TYPES; t++) weights[t] = s.weights[t][n - 1];
        int[] caps = {Integer.MAX_VALUE, Integer.MAX_VALUE, s.maxCreeper, s.maxEnderman};
        waveCounts = WaveComposer.compose(weights, caps, s.mobsPerWave, rnd);
        waveSpawnedByType = new int[WaveComposer.TYPES];
        spawnedThisWave = 0;
        killedThisWave = 0;
        glowedLast = false;
        mobs.clear();
        Location altar = forge.altarLocation();
        rfx.waveStart(altar, forge.state().radius, n, s.waves);
        boolean last = n == s.waves;
        titleArena(Title.title(
                Text.mm(last ? "<dark_red><bold>FINAL WAVE" : "<red><bold>WAVE " + n),
                Text.mm("<gray>" + s.mobsPerWave + " foes rise" + (last ? " - the Last King's guard" : "")),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(600))));
        audit.log("RITUAL", "wave " + n + " begins: Z" + waveCounts[0] + " S" + waveCounts[1] + " C" + waveCounts[2] + " E" + waveCounts[3]);
        updateBar();

        int[] order = new int[s.mobsPerWave];
        int idx = 0;
        for (int t = 0; t < WaveComposer.TYPES; t++) for (int c = 0; c < waveCounts[t]; c++) order[idx++] = t;
        for (int i = order.length - 1; i > 0; i--) { // shuffle so types intermix
            int j = rnd.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }
        final int[] cursor = {0};
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!enc.equals(data().encounterId) || data().phase != Phase.WAVE_ACTIVE) {
                spawnTask.cancel();
                return;
            }
            if (cursor[0] >= order.length) {
                spawnTask.cancel();
                spawnTask = null;
                return;
            }
            spawnMob(order[cursor[0]++], n);
        }, 20L, s.spawnIntervalTicks);
    }

    private Location spawnPoint(Location altar, ForgeState st) {
        World w = altar.getWorld();
        for (int i = 0; i < 24; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            double rr = st.radius - 2.0 - rnd.nextDouble() * 3.0;
            double x = st.centerX + 0.5 + Math.cos(a) * rr, z = st.centerZ + 0.5 + Math.sin(a) * rr;
            Location l = new Location(w, x, st.floorY + 1, z);
            if (safeStand(l)) return l;
        }
        return new Location(w, st.centerX + 0.5 + st.radius - 3, st.floorY + 1, st.centerZ + 0.5);
    }

    private boolean safeStand(Location l) {
        Block feet = l.getBlock();
        Block head = feet.getRelative(0, 1, 0);
        Block head2 = feet.getRelative(0, 2, 0);
        Block floor = feet.getRelative(0, -1, 0);
        return feet.isPassable() && head.isPassable() && head2.isPassable() && !feet.isLiquid() && floor.getType().isSolid();
    }

    private void spawnMob(int type, int wave) {
        ForgeState st = forge.state();
        Location altar = forge.altarLocation();
        World w = altar.getWorld();
        Location at = spawnPoint(altar, st);
        Class<? extends Mob> cls = switch (type) {
            case WaveComposer.SKELETON -> Skeleton.class;
            case WaveComposer.CREEPER -> Creeper.class;
            case WaveComposer.ENDERMAN -> Enderman.class;
            default -> Zombie.class;
        };
        String enc = data().encounterId;
        Mob mob = w.spawn(at, cls, m -> {
            m.getPersistentDataContainer().set(Keys.RITUAL_MOB, PersistentDataType.STRING, enc);
            m.getPersistentDataContainer().set(Keys.RITUAL_WAVE, PersistentDataType.INTEGER, wave);
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.setCanPickupItems(false);
            if (m instanceof Zombie z) z.setShouldBurnInDay(false);
            if (m instanceof Skeleton sk) sk.setShouldBurnInDay(false);
            if (m instanceof Creeper c && wave >= s.waves && rnd.nextInt(4) == 0) c.setPowered(true);
            scale(m, wave);
            gear(m, wave);
        });
        MobTrack tr = new MobTrack();
        tr.type = type;
        tr.lastPos = mob.getLocation();
        tr.lastMoveMs = now();
        mobs.put(mob.getUniqueId(), tr);
        spawnedThisWave++;
        waveSpawnedByType[type]++;
        rfx.mobSpawn(at, wave);
        retarget(mob);
        updateBar();
    }

    private void scale(Mob m, int wave) {
        int i = Math.max(0, Math.min(wave - 1, s.healthMult.length - 1));
        AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null && s.healthMult[i] != 1.0) {
            hp.addModifier(new AttributeModifier(Keys.MOB_HEALTH, s.healthMult[i] - 1.0, AttributeModifier.Operation.ADD_SCALAR));
            m.setHealth(hp.getValue());
        }
        AttributeInstance dmg = m.getAttribute(Attribute.ATTACK_DAMAGE);
        if (dmg != null && s.damageMult[i] != 1.0) {
            dmg.addModifier(new AttributeModifier(Keys.MOB_DAMAGE, s.damageMult[i] - 1.0, AttributeModifier.Operation.ADD_SCALAR));
        }
        AttributeInstance fr = m.getAttribute(Attribute.FOLLOW_RANGE);
        if (fr != null) fr.setBaseValue(64.0);
    }

    private void gear(Mob m, int wave) {
        EntityEquipment eq = m.getEquipment();
        if (eq == null || m instanceof Creeper || m instanceof Enderman) return;
        if (wave >= 2) eq.setHelmet(new ItemStack(wave >= 4 ? Material.IRON_HELMET : Material.CHAINMAIL_HELMET));
        if (wave >= 3) eq.setChestplate(new ItemStack(wave >= 5 ? Material.IRON_CHESTPLATE : Material.CHAINMAIL_CHESTPLATE));
        if (wave >= 4) eq.setLeggings(new ItemStack(Material.CHAINMAIL_LEGGINGS));
        if (m instanceof Zombie && wave >= 2) eq.setItemInMainHand(new ItemStack(wave >= 4 ? Material.IRON_SWORD : Material.STONE_SWORD));
        eq.setHelmetDropChance(0f);
        eq.setChestplateDropChance(0f);
        eq.setLeggingsDropChance(0f);
        eq.setBootsDropChance(0f);
        eq.setItemInMainHandDropChance(0f);
        eq.setItemInOffHandDropChance(0f);
    }

    public boolean isRitualMob(Entity e) {
        return e.getPersistentDataContainer().has(Keys.RITUAL_MOB, PersistentDataType.STRING);
    }

    public boolean isCurrentRitualMob(Entity e) {
        String enc = e.getPersistentDataContainer().get(Keys.RITUAL_MOB, PersistentDataType.STRING);
        return enc != null && enc.equals(data().encounterId) && mobs.containsKey(e.getUniqueId());
    }

    // ------------------------------------------------------------------ mob lifecycle

    public void onMobDeath(LivingEntity e) {
        if (!isCurrentRitualMob(e)) return;
        if (mobs.remove(e.getUniqueId()) == null) return; // exactly-once accounting
        killedThisWave++;
        afterMobGone();
    }

    /** A ritual mob disappeared without dying (void, despawn, unload, plugin): replace it so the wave stays at exactly N. */
    public void onMobLost(UUID id) {
        MobTrack t = mobs.remove(id);
        if (t == null || data().phase != Phase.WAVE_ACTIVE) return;
        spawnedThisWave--;
        waveSpawnedByType[t.type]--;
        audit.log("RITUAL", "ritual mob " + id + " vanished; spawning a replacement");
        spawnMob(t.type, data().wave);
        updateBar();
    }

    private void afterMobGone() {
        updateBar();
        int remaining = mobs.size();
        boolean allSpawned = spawnedThisWave >= s.mobsPerWave;
        if (allSpawned && remaining == 1 && !glowedLast) {
            glowedLast = true;
            UUID id = mobs.keySet().iterator().next();
            Entity e = Bukkit.getEntity(id);
            if (e instanceof LivingEntity le) {
                le.setGlowing(true);
                rfx.lastEnemy(le.getLocation());
                titleArena(Title.title(Text.mm(""), Text.mm("<red>One foe remains"),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(400))));
            }
        }
        if (allSpawned && remaining == 0) waveCleared();
    }

    private void waveCleared() {
        RitualData d = data();
        if (d.phase != Phase.WAVE_ACTIVE) return;
        String enc = d.encounterId;
        int wave = d.wave;
        d.phase = Phase.WAVE_TRANSITION;
        d.lastTransitionMs = now();
        store.saveAsync();
        Location altar = forge.altarLocation();
        rfx.waveComplete(altar, forge.state().radius, wave);
        audit.log("RITUAL", "wave " + wave + " cleared (" + killedThisWave + " kills)");
        if (wave >= s.waves) {
            startRewardSequence();
            return;
        }
        titleArena(Title.title(Text.mm("<gold><bold>WAVE " + wave + " CLEARED"), Text.mm("<gray>The runes gather strength..."),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2400), Duration.ofMillis(500))));
        int ticks = Math.max(20, s.transitionSeconds * 20);
        for (int t = 0; t < ticks; t += 5) {
            final int tt = t;
            later(enc, t, () -> rfx.transitionTick(altar, forge.state().radius, tt));
        }
        later(enc, ticks, () -> startWave(wave + 1));
    }

    // ------------------------------------------------------------------ reward

    private void startRewardSequence() {
        RitualData d = data();
        String enc = d.encounterId;
        d.phase = Phase.REWARD_SEQUENCE;
        d.lastTransitionMs = now();
        store.saveNow();
        Location altar = forge.altarLocation();
        int ticks = Math.max(40, s.rewardSeconds * 20);
        titleArena(Title.title(Text.mm("<dark_red><bold>THE TRIAL IS WON"), Text.mm("<gray>Something stirs at the altar..."),
                Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(3500), Duration.ofMillis(800))));
        rfx.rewardSequence(altar, forge.state().radius, ticks);
        later(enc, ticks, this::revealReward);
    }

    private void revealReward() {
        RitualData d = data();
        if (d.phase != Phase.REWARD_SEQUENCE) return;
        Location altar = forge.altarLocation();
        Location at = altar.clone().add(0.5, 1.5, 0.5);
        var item = weapons.spawnReward(at);
        d.phase = Phase.REWARD_PENDING;
        d.lastTransitionMs = now();
        store.saveNow();
        if (item == null) {
            audit.log("RITUAL", "reward could not be spawned (weapon already exists?)");
            return;
        }
        rfx.rewardReveal(at);
        broadcastNear(Text.mm("<dark_red>☠ <gray>The blade rises from the anvil. <red>Take it - if you can."));
        audit.log("RITUAL", "trial complete; Oathbreaker appeared at the altar");
    }

    /** The reward was claimed: the forge falls silent for good. */
    public void onClaimed() {
        RitualData d = data();
        if (d.phase == Phase.COMPLETED) return;
        d.phase = Phase.COMPLETED;
        d.lastTransitionMs = now();
        store.saveNow();
        cancelTasks();
        runesOff(false);
        forge.releaseTickets();
        hideBar();
        audit.log("RITUAL", "forge completed; Oathbreaker claimed");
    }

    // ------------------------------------------------------------------ failure / admin reset

    public void fail(String reason) {
        RitualData d = data();
        if (!d.inProgress() || d.phase == Phase.REWARD_SEQUENCE) return;
        String enc = d.encounterId;
        d.phase = Phase.FAILED;
        d.lastFailReason = reason;
        d.retryAvailableAtMs = now() + s.retryCooldownMinutes * 60_000L;
        d.lastTransitionMs = now();
        store.saveNow();
        cancelTasks();
        Location altar = forge.altarLocation();
        cleanupMobs();
        hideBar();
        if (altar != null) {
            rfx.fail(altar, forge.state().radius);
            runesOff(false);
        }
        forge.releaseTickets();
        broadcastNear(Text.mm("<dark_red>☠ <gray>The trial has failed: <red>" + reason + "<gray>. The forge goes dark."));
        audit.log("RITUAL", "attempt #" + d.attempts + " FAILED (" + reason + "), encounter " + enc + "; retry in "
                + s.retryCooldownMinutes + " min");
    }

    /** Admin: abandon whatever is going on and return to a clean state. */
    public void adminReset(boolean clearCooldown, String actor) {
        RitualData d = data();
        cancelTasks();
        cleanupMobs();
        hideBar();
        d.encounterId = null;
        d.wave = 0;
        d.phase = weapons.ledger().exists() ? Phase.COMPLETED : Phase.IDLE;
        if (clearCooldown) d.retryAvailableAtMs = 0;
        d.lastFailReason = null;
        store.saveNow();
        runesOff(false);
        forge.releaseTickets();
        audit.log("ADMIN", actor + " reset the ritual (clearCooldown=" + clearCooldown + ") -> " + d.phase);
    }

    private void cleanupMobs() {
        List<UUID> ids = new ArrayList<>(mobs.keySet());
        mobs.clear(); // before removing, so removal events are not mistaken for lost mobs
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        purgeArenaMobs();
    }

    /** Remove every tagged ritual mob in the arena chunks (never a world scan). */
    public int purgeArenaMobs() {
        World w = forge.world();
        if (w == null || !forge.state().present()) return 0;
        ForgeState st = forge.state();
        int removed = 0;
        int e = st.radius + 6;
        for (int cx = (st.centerX - e) >> 4; cx <= (st.centerX + e) >> 4; cx++) {
            for (int cz = (st.centerZ - e) >> 4; cz <= (st.centerZ + e) >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) continue;
                for (Entity en : w.getChunkAt(cx, cz).getEntities()) {
                    if (isRitualMob(en)) {
                        en.remove();
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------ population / targeting

    public boolean eligible(Player p) {
        return p.isOnline() && !p.isDead() && s.eligibleModes.contains(p.getGameMode()) && forge.inArena(p.getLocation());
    }

    public List<Player> arenaPlayers() {
        List<Player> out = new ArrayList<>();
        World w = forge.world();
        if (w == null) return out;
        for (Player p : w.getPlayers()) if (eligible(p)) out.add(p);
        return out;
    }

    public Player nearestEligible(Location from) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : arenaPlayers()) {
            double d = p.getLocation().distanceSquared(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** Point a ritual mob at the nearest eligible living player in the arena (no line-of-sight requirement). */
    public void retarget(Mob m) {
        Player t = nearestEligible(m.getLocation());
        if (t != null) m.setTarget(t);
    }

    /** Called by listeners after anything that can change who is inside (death, quit, teleport, respawn). */
    public void scheduleCheck() {
        if (!active() || checkPending) return;
        checkPending = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            checkPending = false;
            checkPopulation();
        }, 2L);
    }

    private void checkPopulation() {
        Phase p = data().phase;
        if (p != Phase.STARTING && p != Phase.WAVE_ACTIVE && p != Phase.WAVE_TRANSITION) return;
        if (arenaPlayers().isEmpty()) fail("no living challenger remained in the arena");
    }

    private void poll() {
        pollCounter++;
        RitualData d = data();
        if (!d.inProgress()) {
            if (bar != null && !barViewers.isEmpty()) hideBar();
            return;
        }
        checkPopulation();
        if (!d.inProgress()) return;
        List<Player> inside = arenaPlayers();
        updateBarAudience();
        if (d.phase == Phase.WAVE_ACTIVE) watchdog(inside);
    }

    private void watchdog(List<Player> inside) {
        ForgeState st = forge.state();
        Location altar = forge.altarLocation();
        long t = now();
        boolean stallCheck = pollCounter % Math.max(1, s.stallCheckTicks / Math.max(1, s.alivePollTicks)) == 0;
        for (Iterator<Map.Entry<UUID, MobTrack>> it = mobs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, MobTrack> en = it.next();
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof Mob m) || !m.isValid() || m.isDead()) {
                if (e == null || !e.isValid()) {
                    it.remove();
                    spawnedThisWave--;
                    waveSpawnedByType[en.getValue().type]--;
                    audit.log("RITUAL", "tracked ritual mob " + en.getKey() + " is gone; replacing");
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (data().phase == Phase.WAVE_ACTIVE) spawnMob(en.getValue().type, data().wave);
                    });
                }
                continue;
            }
            Location l = m.getLocation();
            // containment: knocked / pushed outside -> back inside
            if (!forge.inArena(l.getX(), l.getY(), l.getZ())) {
                Location back = spawnPoint(altar, st);
                m.teleport(back);
                en.getValue().lastPos = back;
                en.getValue().lastMoveMs = t;
                continue;
            }
            // purposeful: always have a valid target
            LivingEntity tg = m.getTarget();
            if (!(tg instanceof Player tp) || !eligible(tp)) {
                if (!inside.isEmpty()) {
                    Player near = nearestEligible(l);
                    if (near != null) m.setTarget(near);
                } else {
                    m.setTarget(null);
                }
            }
            if (stallCheck) {
                MobTrack tr = en.getValue();
                if (tr.lastPos.getWorld() != l.getWorld() || tr.lastPos.distanceSquared(l) > 0.6 * 0.6) {
                    tr.lastPos = l;
                    tr.lastMoveMs = t;
                } else if (t - tr.lastMoveMs > s.stallTeleportSeconds * 1000L && m.getTarget() instanceof Player tp2) {
                    Location near = tp2.getLocation().add(new Vector(rnd.nextDouble() * 4 - 2, 0, rnd.nextDouble() * 4 - 2));
                    near.setY(tp2.getLocation().getY());
                    if (safeStand(near) && forge.inArena(near)) {
                        m.teleport(near);
                        rfx.mobSpawn(near, Math.max(1, data().wave));
                    }
                    tr.lastPos = m.getLocation();
                    tr.lastMoveMs = t;
                }
            }
        }
    }

    /** A valid in-arena position for an Enderman that tried to teleport somewhere else. */
    public Location redirectTeleport(Location from) {
        ForgeState st = forge.state();
        Player target = nearestEligible(from);
        for (int i = 0; i < 16; i++) {
            Location c;
            if (target != null && i < 10) {
                c = target.getLocation().add(rnd.nextDouble() * 6 - 3, 0, rnd.nextDouble() * 6 - 3);
                c.setY(target.getLocation().getY());
            } else {
                c = spawnPoint(forge.altarLocation(), st);
            }
            c = c.getBlock().getLocation().add(0.5, 0, 0.5);
            if (forge.inArena(c) && safeStand(c)) return c;
        }
        return null;
    }

    public boolean validArenaDestination(Location to) {
        return to != null && forge.inArena(to) && safeStand(to.getBlock().getLocation());
    }

    // ------------------------------------------------------------------ presentation helpers

    private void titleArena(Title t) {
        for (Player p : playersNear(60)) p.showTitle(t);
    }

    private List<Player> playersNear(double radius) {
        List<Player> out = new ArrayList<>();
        World w = forge.world();
        Location c = forge.arenaCentre();
        if (w == null || c == null) return out;
        double r2 = radius * radius;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(c) <= r2) out.add(p);
        return out;
    }

    private void broadcastNear(net.kyori.adventure.text.Component msg) {
        for (Player p : playersNear(80)) p.sendMessage(msg);
    }

    private void updateBar() {
        RitualData d = data();
        if (!d.inProgress()) return;
        if (bar == null) {
            bar = BossBar.bossBar(Text.mm(""), 1f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
        }
        int remaining = mobs.size() + Math.max(0, s.mobsPerWave - spawnedThisWave);
        String txt = d.phase == Phase.WAVE_ACTIVE
                ? "<dark_red><bold>TRIAL OF ASH</bold> <gray>- <red>Wave " + d.wave + "/" + s.waves + " <gray>- <white>" + remaining + "<gray> foes remain"
                : d.phase == Phase.WAVE_TRANSITION ? "<dark_red><bold>TRIAL OF ASH</bold> <gray>- <gold>Wave " + d.wave + " cleared"
                : "<dark_red><bold>TRIAL OF ASH</bold> <gray>- <red>the runes ignite";
        bar.name(Text.mm(txt));
        bar.progress(d.phase == Phase.WAVE_ACTIVE ? Math.max(0f, Math.min(1f, remaining / (float) Math.max(1, s.mobsPerWave))) : 1f);
    }

    private void updateBarAudience() {
        updateBar();
        if (bar == null) return;
        Set<UUID> want = new HashSet<>();
        for (Player p : playersNear(forge.state().radius + 14)) want.add(p.getUniqueId());
        for (UUID id : new ArrayList<>(barViewers)) {
            if (!want.contains(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(bar);
                barViewers.remove(id);
            }
        }
        for (UUID id : want) {
            if (barViewers.add(id)) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.showBossBar(bar);
            }
        }
    }

    private void hideBar() {
        if (bar == null) return;
        for (UUID id : barViewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        barViewers.clear();
    }

    // ------------------------------------------------------------------ diagnostics

    public List<String> describe() {
        RitualData d = data();
        List<String> out = new ArrayList<>();
        out.add("phase=" + d.phase + " wave=" + d.wave + "/" + s.waves + " attempts=" + d.attempts);
        out.add("encounter=" + d.encounterId + " initiator=" + d.initiatorName);
        if (d.phase == Phase.FAILED) out.add("retry in " + retryRemainingSeconds() + "s (" + d.lastFailReason + ")");
        out.add("tracked mobs=" + mobs.size() + " spawnedThisWave=" + spawnedThisWave + "/" + s.mobsPerWave + " killedThisWave=" + killedThisWave);
        out.add("arena players=" + arenaPlayers().size());
        return out;
    }

    public Map<String, Integer> mobCountsByType() {
        Map<String, Integer> out = new HashMap<>();
        for (UUID id : mobs.keySet()) {
            Entity e = Bukkit.getEntity(id);
            out.merge(e == null ? "UNLOADED" : e.getType().name(), 1, Integer::sum);
        }
        return out;
    }
}
