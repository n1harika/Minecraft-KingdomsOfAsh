package com.kingdomsofash.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.GameMode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Typed, validated snapshot of config.yml. Missing keys fall back to the defaults bundled in the jar
 * (JavaPlugin merges them), invalid values are clamped and logged, never thrown.
 */
public final class Settings {
    private final FileConfiguration c;
    private final Logger log;

    // commands
    public List<String> ability1Aliases;
    public List<String> ultimateAliases;

    // weapon
    public Material weaponMaterial;
    public double weaponAttackDamage;
    public double weaponAttackSpeedModifier;
    public String customModelString;
    public int integrityScanSeconds;
    public int missingChecksBeforeRecovery;
    public boolean autoRecoverMissing;
    public int combatLogSeconds;
    public boolean dropOnKeepInventory;
    public boolean broadcastOwnershipChange;
    public boolean revealDropCoordinates;
    public boolean droppedBeam;

    // blood oath
    public int hitsPerRage;
    public int maxRage;
    public double decayDelaySeconds;
    public double decayIntervalSeconds;

    // rupture
    public int ruptureRageCost;
    public double ruptureCooldownSeconds;
    public double ruptureWidth;
    public double ruptureRange;
    public double ruptureVerticalBelow;
    public double ruptureVerticalAbove;
    public double ruptureInitialHearts;
    public double ruptureBleedHearts;
    public double ruptureBleedIntervalSeconds;
    public double ruptureBleedDurationSeconds;
    public boolean ruptureLineOfSight;
    public boolean ruptureShieldBlocks;
    public boolean ruptureTeamFriendlyFire;
    public boolean ruptureIgnorePets;

    // fury
    public int furyRequiredRage;
    public double furyDurationSeconds;
    public double furyCooldownSeconds;
    public double furyDamageMultiplier;
    public double furyAttackSpeedBonus;
    public double furyMoveSpeedBonus;
    public double furyDamageTakenMultiplier;
    public boolean furyPreventFall;
    public boolean furyEndOnWorldChange;
    public double exhaustionSeconds;
    public double exhaustionDamageMultiplier;
    public double exhaustionMovePenalty;
    public boolean armorEnabled;
    public String armorMode;
    public boolean armorShowToSelf;
    public double armorViewerDistance;
    public int armorRefreshTicks;

    // bloodiron
    public boolean bloodironEnabled;
    public Material bloodironHost;
    public List<String> bloodironWorlds;
    public int bloodironRequired;
    public int oreMinY, oreMaxY, oreVirtualMinY;
    public int oreSmallVeins, oreSmallSize, oreBuriedVeins, oreBuriedSize, oreLargeSize;
    public double oreSmallDiscard, oreLargeChance, oreLargeDiscard;
    public Set<Material> oreReplaceable;
    public Material oreDropMaterial;
    public int oreDropMin, oreDropMax, oreXpMin, oreXpMax;
    public boolean allowPlacingOreItem;

    // forge
    public boolean forgeAutoGenerate;
    public String forgeWorld;
    public int forgeSearchRadius;
    public int forgeMinDistance;
    public int forgeMaxAttempts;
    public int arenaRadius;
    public int arenaHeight;
    public int forgeBlocksPerTick;
    public int forgeMinRockCover;
    public boolean ambientEnabled;
    public int ambientRange;
    public int ambientInterval;
    public boolean protTerrain, protExplosions, protBlocks, protFluids, protPistons, protMobGriefing, protFire, protNaturalSpawns;

    // ritual
    public int waves;
    public int mobsPerWave;
    public int retryCooldownMinutes;
    public int introSeconds;
    public int transitionSeconds;
    public int rewardSeconds;
    public int spawnIntervalTicks;
    public Set<GameMode> eligibleModes;
    public int alivePollTicks;
    public double[][] weights; // [type: Z,S,C,E][wave]
    public int maxEnderman;
    public int maxCreeper;
    public double[] healthMult;
    public double[] damageMult;
    public boolean mobDrops;
    public boolean mobXp;
    public int stallCheckTicks;
    public int stallTeleportSeconds;
    public String onRestart;

    // effects
    public double particleDensity;
    public double soundVolume;
    public double effectDistance;
    public boolean tpsThrottle;
    public double tpsBelow;
    public int decorativeBudget;
    public boolean weaponAura;
    public boolean swingTrail;

    // resource pack
    public String packUrl;
    public String packSha1;
    public String packUuid;
    public String packPrompt;
    public boolean packForce;
    public boolean packAcceptAny;

    public Settings(FileConfiguration c, Logger log) {
        this.c = c;
        this.log = log;
        reload();
    }

    public FileConfiguration raw() {
        return c;
    }

    public void reload() {
        ability1Aliases = c.getStringList("commands.ability1-aliases");
        ultimateAliases = c.getStringList("commands.ultimate-aliases");

        weaponMaterial = material("weapon.material", Material.NETHERITE_SWORD);
        weaponAttackDamage = d("weapon.attack-damage", 0, 100);
        weaponAttackSpeedModifier = d("weapon.attack-speed-modifier", -3.9, 5);
        customModelString = c.getString("weapon.custom-model-string", "oathbreaker");
        integrityScanSeconds = i("weapon.integrity-scan-seconds", 0, 3600);
        missingChecksBeforeRecovery = i("weapon.missing-checks-before-recovery", 1, 100);
        autoRecoverMissing = c.getBoolean("weapon.auto-recover-missing", true);
        combatLogSeconds = i("weapon.combat-log-seconds", 0, 600);
        dropOnKeepInventory = c.getBoolean("weapon.drop-on-keep-inventory", true);
        broadcastOwnershipChange = c.getBoolean("weapon.broadcast-ownership-change", true);
        revealDropCoordinates = c.getBoolean("weapon.reveal-drop-coordinates", false);
        droppedBeam = c.getBoolean("weapon.dropped-beam", true);

        hitsPerRage = i("blood-oath.hits-per-rage", 1, 100);
        maxRage = i("blood-oath.max-rage", 1, 100);
        decayDelaySeconds = d("blood-oath.decay-delay-seconds", 0, 600);
        decayIntervalSeconds = d("blood-oath.decay-interval-seconds", 0.05, 600);

        ruptureRageCost = i("rupture.rage-cost", 0, 100);
        ruptureCooldownSeconds = d("rupture.cooldown-seconds", 0, 86400);
        ruptureWidth = d("rupture.width", 0.5, 64);
        ruptureRange = d("rupture.range", 0.5, 64);
        ruptureVerticalBelow = d("rupture.vertical-below", 0, 16);
        ruptureVerticalAbove = d("rupture.vertical-above", 0, 16);
        ruptureInitialHearts = d("rupture.initial-damage-hearts", 0, 1000);
        ruptureBleedHearts = d("rupture.bleed-damage-hearts", 0, 1000);
        ruptureBleedIntervalSeconds = d("rupture.bleed-interval-seconds", 0.05, 600);
        ruptureBleedDurationSeconds = d("rupture.bleed-duration-seconds", 0, 3600);
        ruptureLineOfSight = c.getBoolean("rupture.require-line-of-sight", true);
        ruptureShieldBlocks = c.getBoolean("rupture.shield-blocks", true);
        ruptureTeamFriendlyFire = c.getBoolean("rupture.respect-team-friendly-fire", true);
        ruptureIgnorePets = c.getBoolean("rupture.ignore-owned-pets", true);

        furyRequiredRage = i("fury.required-rage", 0, 100);
        furyDurationSeconds = d("fury.duration-seconds", 1, 3600);
        furyCooldownSeconds = d("fury.cooldown-seconds", 0, 86400);
        furyDamageMultiplier = d("fury.melee-damage-multiplier", 0, 10);
        furyAttackSpeedBonus = d("fury.attack-speed-bonus", -0.9, 10);
        furyMoveSpeedBonus = d("fury.move-speed-bonus", -0.9, 10);
        furyDamageTakenMultiplier = d("fury.damage-taken-multiplier", 0, 2);
        furyPreventFall = c.getBoolean("fury.prevent-fall-damage", true);
        furyEndOnWorldChange = c.getBoolean("fury.end-on-world-change", true);
        exhaustionSeconds = d("fury.exhaustion.duration-seconds", 0, 600);
        exhaustionDamageMultiplier = d("fury.exhaustion.melee-damage-multiplier", 0, 10);
        exhaustionMovePenalty = d("fury.exhaustion.move-speed-penalty", 0, 0.95);
        armorEnabled = c.getBoolean("fury.spectral-armor.enabled", true);
        armorMode = c.getString("fury.spectral-armor.mode", "AUTO").toUpperCase(Locale.ROOT);
        armorShowToSelf = c.getBoolean("fury.spectral-armor.show-to-self", false);
        armorViewerDistance = d("fury.spectral-armor.viewer-distance", 8, 256);
        armorRefreshTicks = i("fury.spectral-armor.refresh-ticks", 2, 200);

        bloodironEnabled = c.getBoolean("bloodiron.enabled", true);
        bloodironHost = material("bloodiron.host-block", Material.NETHER_GOLD_ORE);
        bloodironWorlds = c.getStringList("bloodiron.worlds");
        bloodironRequired = i("bloodiron.required-per-ritual", 1, 2304);
        oreMinY = i("bloodiron.generation.min-y", -64, 320);
        oreMaxY = i("bloodiron.generation.max-y", -64, 320);
        oreVirtualMinY = i("bloodiron.generation.virtual-min-y", -512, 320);
        oreSmallVeins = i("bloodiron.generation.small-veins-per-chunk", 0, 64);
        oreSmallSize = i("bloodiron.generation.small-vein-size", 1, 64);
        oreSmallDiscard = d("bloodiron.generation.small-air-discard", 0, 1);
        oreBuriedVeins = i("bloodiron.generation.buried-veins-per-chunk", 0, 64);
        oreBuriedSize = i("bloodiron.generation.buried-vein-size", 1, 64);
        oreLargeChance = d("bloodiron.generation.large-vein-chance", 0, 1);
        oreLargeSize = i("bloodiron.generation.large-vein-size", 1, 64);
        oreLargeDiscard = d("bloodiron.generation.large-air-discard", 0, 1);
        oreReplaceable = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("bloodiron.generation.replaceable")) {
            Material m = Material.matchMaterial(s);
            if (m != null && m.isBlock()) oreReplaceable.add(m);
            else log.warning("config: unknown replaceable block '" + s + "'");
        }
        if (oreReplaceable.isEmpty()) oreReplaceable.addAll(List.of(Material.STONE, Material.DEEPSLATE));
        oreDropMaterial = material("bloodiron.drops-without-silk-touch.material", Material.IRON_NUGGET);
        oreDropMin = i("bloodiron.drops-without-silk-touch.min", 0, 64);
        oreDropMax = Math.max(oreDropMin, i("bloodiron.drops-without-silk-touch.max", 0, 64));
        oreXpMin = i("bloodiron.drops-without-silk-touch.xp-min", 0, 1000);
        oreXpMax = Math.max(oreXpMin, i("bloodiron.drops-without-silk-touch.xp-max", 0, 1000));
        allowPlacingOreItem = c.getBoolean("bloodiron.allow-placing-item", false);

        forgeAutoGenerate = c.getBoolean("forge.auto-generate", true);
        forgeWorld = c.getString("forge.world", "");
        forgeSearchRadius = i("forge.search-radius", 64, 30000);
        forgeMinDistance = i("forge.min-distance-from-spawn", 0, forgeSearchRadius);
        forgeMaxAttempts = i("forge.max-search-attempts", 1, 1000);
        arenaRadius = i("forge.arena-radius", 12, 40);
        arenaHeight = i("forge.arena-height", 10, 32);
        forgeBlocksPerTick = i("forge.blocks-per-tick", 100, 100000);
        forgeMinRockCover = i("forge.min-rock-cover", 2, 64);
        ambientEnabled = c.getBoolean("forge.ambient.enabled", true);
        ambientRange = i("forge.ambient.range", 8, 128);
        ambientInterval = i("forge.ambient.interval-ticks", 2, 200);
        protTerrain = c.getBoolean("forge.protection.terrain", true);
        protExplosions = c.getBoolean("forge.protection.explosions", true);
        protBlocks = c.getBoolean("forge.protection.block-place-break", true);
        protFluids = c.getBoolean("forge.protection.fluids", true);
        protPistons = c.getBoolean("forge.protection.pistons", true);
        protMobGriefing = c.getBoolean("forge.protection.mob-griefing", true);
        protFire = c.getBoolean("forge.protection.fire", true);
        protNaturalSpawns = c.getBoolean("forge.protection.block-natural-spawns-in-arena", true);

        waves = i("ritual.waves", 1, 20);
        mobsPerWave = i("ritual.mobs-per-wave", 1, 100);
        retryCooldownMinutes = i("ritual.retry-cooldown-minutes", 0, 100000);
        introSeconds = i("ritual.intro-seconds", 0, 120);
        transitionSeconds = i("ritual.wave-transition-seconds", 0, 120);
        rewardSeconds = i("ritual.reward-sequence-seconds", 1, 120);
        spawnIntervalTicks = i("ritual.spawn-interval-ticks", 1, 100);
        eligibleModes = EnumSet.noneOf(GameMode.class);
        for (String s : c.getStringList("ritual.eligible-gamemodes")) {
            try {
                eligibleModes.add(GameMode.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warning("config: unknown gamemode '" + s + "'");
            }
        }
        if (eligibleModes.isEmpty()) eligibleModes.addAll(List.of(GameMode.SURVIVAL, GameMode.ADVENTURE));
        alivePollTicks = i("ritual.alive-poll-ticks", 2, 200);
        weights = new double[4][waves];
        String[] types = {"ZOMBIE", "SKELETON", "CREEPER", "ENDERMAN"};
        for (int t = 0; t < 4; t++) {
            List<Double> list = c.getDoubleList("ritual.weights." + types[t]);
            for (int w = 0; w < waves; w++) {
                double v = list.isEmpty() ? 1 : list.get(Math.min(w, list.size() - 1));
                weights[t][w] = Math.max(0, v);
            }
        }
        maxEnderman = i("ritual.max-enderman-per-wave", 0, 100);
        maxCreeper = i("ritual.max-creeper-per-wave", 0, 100);
        healthMult = perWave("ritual.health-multiplier", 1.0);
        damageMult = perWave("ritual.damage-multiplier", 1.0);
        mobDrops = c.getBoolean("ritual.mob-drops", false);
        mobXp = c.getBoolean("ritual.mob-xp", false);
        stallCheckTicks = i("ritual.stall.check-ticks", 20, 1200);
        stallTeleportSeconds = i("ritual.stall.teleport-after-seconds", 5, 600);
        onRestart = c.getString("ritual.on-restart", "INTERRUPTED").toUpperCase(Locale.ROOT);

        particleDensity = d("effects.particle-density", 0.05, 5);
        soundVolume = d("effects.sound-volume", 0, 5);
        effectDistance = d("effects.effect-distance", 8, 256);
        tpsThrottle = c.getBoolean("effects.tps-throttle.enabled", true);
        tpsBelow = d("effects.tps-throttle.below-tps", 5, 20);
        decorativeBudget = i("effects.decorative-budget-per-tick", 50, 100000);
        weaponAura = c.getBoolean("effects.weapon-aura", true);
        swingTrail = c.getBoolean("effects.swing-trail", true);

        packUrl = c.getString("resource-pack.url", "");
        packSha1 = c.getString("resource-pack.sha1", "");
        packUuid = c.getString("resource-pack.uuid", "6f1d0c7e-3a52-4c1b-9d0e-5a7b2c4e8f10");
        packPrompt = c.getString("resource-pack.prompt", "");
        packForce = c.getBoolean("resource-pack.force", false);
        packAcceptAny = c.getBoolean("resource-pack.accept-any-pack", true);
    }

    public ConfigurationSection section(String path) {
        return c.getConfigurationSection(path);
    }

    private double[] perWave(String path, double def) {
        List<Double> list = c.getDoubleList(path);
        double[] out = new double[waves];
        for (int w = 0; w < waves; w++) {
            out[w] = list.isEmpty() ? def : Math.max(0.1, list.get(Math.min(w, list.size() - 1)));
        }
        return out;
    }

    private Material material(String path, Material def) {
        String s = c.getString(path);
        Material m = s == null ? null : Material.matchMaterial(s);
        if (m == null) {
            log.warning("config: invalid material at " + path + " ('" + s + "'), using " + def);
            return def;
        }
        return m;
    }

    private double d(String path, double min, double max) {
        double v = c.getDouble(path);
        if (Double.isNaN(v) || v < min || v > max) {
            double cl = Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
            log.warning("config: " + path + "=" + v + " out of range [" + min + ", " + max + "], using " + cl);
            return cl;
        }
        return v;
    }

    private int i(String path, int min, int max) {
        int v = c.getInt(path);
        if (v < min || v > max) {
            int cl = Math.max(min, Math.min(max, v));
            log.warning("config: " + path + "=" + v + " out of range [" + min + ", " + max + "], using " + cl);
            return cl;
        }
        return v;
    }

    /** Names of worlds as a mutable list copy (helper for commands). */
    public List<String> bloodironWorldList() {
        return new ArrayList<>(bloodironWorlds);
    }
}
