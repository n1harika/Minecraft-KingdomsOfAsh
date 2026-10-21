package com.kingdomsofash;

import com.kingdomsofash.admin.OathbreakerCommand;
import com.kingdomsofash.combat.AbilityDispatcher;
import com.kingdomsofash.combat.BleedManager;
import com.kingdomsofash.combat.CombatListener;
import com.kingdomsofash.combat.FuryManager;
import com.kingdomsofash.combat.OathbreakerAbilities;
import com.kingdomsofash.combat.RageManager;
import com.kingdomsofash.combat.RuptureService;
import com.kingdomsofash.combat.SpectralArmor;
import com.kingdomsofash.config.Settings;
import com.kingdomsofash.forge.ForgeAmbient;
import com.kingdomsofash.forge.ForgeProtection;
import com.kingdomsofash.forge.ForgeService;
import com.kingdomsofash.fx.AnnounceFx;
import com.kingdomsofash.fx.Fx;
import com.kingdomsofash.fx.FuryFx;
import com.kingdomsofash.fx.RitualFx;
import com.kingdomsofash.fx.RuptureFx;
import com.kingdomsofash.fx.WeaponFx;
import com.kingdomsofash.ore.BloodironService;
import com.kingdomsofash.ore.OreListener;
import com.kingdomsofash.persist.StateStore;
import com.kingdomsofash.ritual.RitualListener;
import com.kingdomsofash.ritual.RitualManager;
import com.kingdomsofash.util.Audit;
import com.kingdomsofash.util.Keys;
import com.kingdomsofash.util.Signer;
import com.kingdomsofash.util.Text;
import com.kingdomsofash.weapon.OathbreakerItem;
import com.kingdomsofash.weapon.WeaponFlowListener;
import com.kingdomsofash.weapon.WeaponService;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.URI;
import java.util.HexFormat;
import java.util.UUID;

/** Kingdoms of Ash - wiring and lifecycle. All gameplay lives in the sub-packages. */
public final class KingdomsOfAshPlugin extends JavaPlugin implements Listener {
    private Settings settings;
    private StateStore store;
    private Audit audit;
    private Fx fx;
    private WeaponService weapons;
    private RageManager rage;
    private BleedManager bleed;
    private FuryManager fury;
    private SpectralArmor armor;
    private ForgeService forge;
    private RitualManager ritual;
    private BloodironService bloodiron;
    private AbilityDispatcher dispatcher;
    private ForgeAmbient ambient;
    private WeaponFx weaponFx;
    private RitualFx ritualFx;
    private AnnounceFx announce;
    private FuryFx furyFx;
    private RuptureFx ruptureFx;
    private RuptureService rupture;
    private OreListener oreListener;
    private boolean started;

    @Override
    public void onEnable() {
        Keys.init(this);
        saveDefaultConfig();
        try {
            settings = new Settings(getConfig(), getLogger());
            Text.prefix = getConfig().getString("messages.prefix", Text.prefix);
            var dir = getDataFolder().toPath();
            store = new StateStore(dir, getLogger());
            store.load();
            Signer signer = Signer.loadOrCreate(dir);
            audit = new Audit(dir, getLogger());

            fx = new Fx(this, settings);
            fx.start();
            weaponFx = new WeaponFx(fx);
            ruptureFx = new RuptureFx(fx);
            furyFx = new FuryFx(fx);
            ritualFx = new RitualFx(fx);
            announce = new AnnounceFx(fx, settings);

            OathbreakerItem item = new OathbreakerItem(settings, signer);
            weapons = new WeaponService(this, settings, store, audit, item, announce);

            rage = new RageManager(this, settings, weaponFx);
            bleed = new BleedManager(this, settings, ruptureFx);
            armor = new SpectralArmor(this, settings);
            fury = new FuryManager(this, settings, weapons, rage, store, audit, furyFx, armor);
            rupture = new RuptureService(settings, weapons, rage, bleed, store, ruptureFx);

            bloodiron = new BloodironService(this, settings, signer);
            forge = new ForgeService(this, settings, store, audit);
            ritual = new RitualManager(this, settings, store, audit, forge, weapons, bloodiron, ritualFx, fx);
            ambient = new ForgeAmbient(this, settings, forge, ritual, weapons, ritualFx, weaponFx);

            dispatcher = new AbilityDispatcher(this);
            dispatcher.register(new OathbreakerAbilities(weapons, rupture, fury));
            dispatcher.registerAliases(settings.ability1Aliases, false);
            dispatcher.registerAliases(settings.ultimateAliases, true);

            var pm = Bukkit.getPluginManager();
            pm.registerEvents(this, this);
            pm.registerEvents(new WeaponFlowListener(this, settings, weapons, announce), this);
            pm.registerEvents(new CombatListener(this, settings, weapons, rage, bleed, fury, armor, weaponFx), this);
            oreListener = new OreListener(this, settings, bloodiron);
            pm.registerEvents(oreListener, this);
            pm.registerEvents(new ForgeProtection(this, settings, forge), this);
            pm.registerEvents(new RitualListener(this, settings, ritual, forge, bloodiron, weapons), this);

            bindCommand("ability1", (sender, cmd, label, args) -> route(sender, false));
            bindCommand("ultimate", (sender, cmd, label, args) -> route(sender, true));
            OathbreakerCommand admin = new OathbreakerCommand(this, settings, store, audit, weapons, forge, ritual, bloodiron, rage,
                    fury, bleed, ruptureFx, furyFx, weaponFx, ritualFx, announce, fx, oreListener.generator());
            PluginCommand pc = getCommand("oathbreaker");
            if (pc != null) {
                pc.setExecutor(admin);
                pc.setTabCompleter(admin);
            }

            rage.start();
            bleed.start();
            fury.start();
            armor.start();
            ambient.start();

            Bukkit.getScheduler().runTaskTimer(this, () -> {
                rage.retainOnly(weapons.ledger().holderUuid());
                weapons.verifyHolder();
            }, 100L, 100L);
            Bukkit.getScheduler().runTaskTimer(this, ambient::beaconTick, 40L, 10L);
            if (settings.integrityScanSeconds > 0) {
                long t = settings.integrityScanSeconds * 20L;
                Bukkit.getScheduler().runTaskTimer(this, weapons::integritySweep, t, t);
            }

            // world-dependent bootstrap runs once all worlds exist
            Bukkit.getScheduler().runTask(this, this::bootstrapWorlds);
            started = true;
            getLogger().info("Kingdoms of Ash enabled. Oathbreaker ledger phase: " + weapons.ledger().phase
                    + ", forge: " + store.state().forge.status + ", ritual: " + store.state().ritual.phase);
        } catch (IOException | RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "Kingdoms of Ash could not start safely and will disable itself", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void bindCommand(String name, org.bukkit.command.CommandExecutor ex) {
        PluginCommand pc = getCommand(name);
        if (pc != null) pc.setExecutor(ex);
    }

    private boolean route(org.bukkit.command.CommandSender sender, boolean ultimate) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        dispatcher.dispatch(p, ultimate);
        return true;
    }

    private void bootstrapWorlds() {
        try {
            forge.bootstrap();
            ritual.bootstrap();
            if (store.state().weapon.phase == com.kingdomsofash.weapon.WeaponLedger.Phase.REWARD_PENDING && forge.ready()) {
                Location altar = forge.altarLocation();
                if (altar != null) forge.withChunkLoaded(altar, () -> weapons.ensureRewardEntity(altar.clone().add(0.5, 1.4, 0.5)));
            }
        } catch (RuntimeException e) {
            getLogger().log(java.util.logging.Level.SEVERE, "bootstrap failed", e);
        }
    }

    @Override
    public void onDisable() {
        if (!started) {
            if (store != null) store.close();
            return;
        }
        try {
            if (ritual != null) ritual.shutdown();
            if (fury != null) fury.shutdown();
            if (armor != null) armor.stop();
            if (bleed != null) bleed.stop();
            if (rage != null) rage.stop();
            if (ambient != null) ambient.stop();
            if (forge != null) forge.shutdown();
            if (fx != null) fx.stop();
            Bukkit.getScheduler().cancelTasks(this);
        } finally {
            if (store != null) store.close();
            if (audit != null) audit.close();
        }
    }

    // ------------------------------------------------------------------ resource pack plumbing

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        String url = settings.packUrl;
        if (url == null || url.isBlank()) return;
        try {
            UUID id = UUID.fromString(settings.packUuid);
            byte[] hash = settings.packSha1 == null || settings.packSha1.isBlank() ? new byte[0] : HexFormat.of().parseHex(settings.packSha1);
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo().id(id).uri(URI.create(url))
                    .hash(HexFormat.of().formatHex(hash)).build();
            ResourcePackRequest req = ResourcePackRequest.resourcePackRequest().packs(info)
                    .prompt(Text.mm(settings.packPrompt)).required(settings.packForce).build();
            e.getPlayer().sendResourcePacks(req);
        } catch (RuntimeException ex) {
            getLogger().warning("resource-pack settings are invalid: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        boolean ours = settings.packAcceptAny || e.getID().toString().equalsIgnoreCase(settings.packUuid);
        if (!ours) return;
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> armor.packLoaded(e.getPlayer().getUniqueId(), true);
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> armor.packLoaded(e.getPlayer().getUniqueId(), false);
            default -> {
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        armor.forget(e.getPlayer().getUniqueId());
    }

}
