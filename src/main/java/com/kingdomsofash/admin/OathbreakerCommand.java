package com.kingdomsofash.admin;

import com.kingdomsofash.combat.BleedManager;
import com.kingdomsofash.combat.FuryManager;
import com.kingdomsofash.combat.RageManager;
import com.kingdomsofash.config.Settings;
import com.kingdomsofash.forge.ForgeService;
import com.kingdomsofash.fx.AnnounceFx;
import com.kingdomsofash.fx.Fx;
import com.kingdomsofash.fx.FuryFx;
import com.kingdomsofash.fx.RitualFx;
import com.kingdomsofash.fx.RuptureFx;
import com.kingdomsofash.fx.WeaponFx;
import com.kingdomsofash.ore.BloodironService;
import com.kingdomsofash.ore.OreGenerator;
import com.kingdomsofash.persist.ForgeState;
import com.kingdomsofash.persist.StateStore;
import com.kingdomsofash.ritual.RitualManager;
import com.kingdomsofash.util.Audit;
import com.kingdomsofash.util.Text;
import com.kingdomsofash.weapon.WeaponLedger;
import com.kingdomsofash.weapon.WeaponService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /oathbreaker} - operator-only diagnostics, testing and controlled recovery. Authorisation is by
 * permission node ({@code kingdomsofash.admin}, default op) checked here again in code, never by name.
 * Every state-changing action requires an explicit {@code confirm} and is written to the audit log.
 */
public final class OathbreakerCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final Settings s;
    private final StateStore store;
    private final Audit audit;
    private final WeaponService weapons;
    private final ForgeService forge;
    private final RitualManager ritual;
    private final BloodironService bloodiron;
    private final RageManager rage;
    private final FuryManager fury;
    private final BleedManager bleed;
    private final RuptureFx ruptureFx;
    private final FuryFx furyFx;
    private final WeaponFx weaponFx;
    private final RitualFx ritualFx;
    private final AnnounceFx announce;
    private final Fx fx;
    private final OreGenerator generator;

    public OathbreakerCommand(Plugin plugin, Settings s, StateStore store, Audit audit, WeaponService weapons, ForgeService forge,
                              RitualManager ritual, BloodironService bloodiron, RageManager rage, FuryManager fury, BleedManager bleed,
                              RuptureFx ruptureFx, FuryFx furyFx, WeaponFx weaponFx, RitualFx ritualFx, AnnounceFx announce, Fx fx,
                              OreGenerator generator) {
        this.plugin = plugin;
        this.s = s;
        this.store = store;
        this.audit = audit;
        this.weapons = weapons;
        this.forge = forge;
        this.ritual = ritual;
        this.bloodiron = bloodiron;
        this.rage = rage;
        this.fury = fury;
        this.bleed = bleed;
        this.ruptureFx = ruptureFx;
        this.furyFx = furyFx;
        this.weaponFx = weaponFx;
        this.ritualFx = ritualFx;
        this.announce = announce;
        this.fx = fx;
        this.generator = generator;
    }

    private static final List<String> SUBS = List.of("help", "status", "holder", "history", "forge", "ritual", "mobs", "cooldowns", "give",
            "recover", "verify", "audit", "bloodiron", "fx", "reload");

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (!sender.hasPermission("kingdomsofash.admin")) {
            sender.sendMessage(Text.mm("<red>You do not have permission."));
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        try {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "status" -> status(sender);
                case "holder" -> holder(sender);
                case "history" -> history(sender, a.length > 1 ? parse(a[1], 15) : 15);
                case "forge" -> forgeCmd(sender, a);
                case "ritual" -> ritualCmd(sender, a);
                case "mobs" -> mobs(sender);
                case "cooldowns" -> cooldowns(sender);
                case "give" -> give(sender, a);
                case "recover" -> recover(sender, a);
                case "verify" -> verify(sender);
                case "audit" -> {
                    for (String l : audit.tail(a.length > 1 ? parse(a[1], 15) : 15)) Text.raw(sender, "<dark_gray>" + Text.esc(l));
                }
                case "bloodiron" -> bloodironCmd(sender, a);
                case "fx" -> fxCmd(sender, a);
                case "reload" -> {
                    plugin.reloadConfig();
                    s.reload();
                    Text.send(sender, "<gray>Configuration reloaded. <dark_gray>(structural options apply to future builds only)");
                    audit.log("ADMIN", sender.getName() + " reloaded the configuration");
                }
                default -> help(sender);
            }
        } catch (RuntimeException e) {
            Text.send(sender, "<red>Command failed: " + Text.esc(String.valueOf(e.getMessage())));
            plugin.getLogger().log(java.util.logging.Level.WARNING, "admin command failed", e);
        }
        return true;
    }

    private static int parse(String s, int def) {
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean confirmed(String[] a) {
        return a.length > 0 && a[a.length - 1].equalsIgnoreCase("confirm");
    }

    private void help(CommandSender c) {
        Text.raw(c, "<dark_red><bold>Kingdoms of Ash <gray>- /oathbreaker");
        String[] lines = {
                "status                      <dark_gray>weapon + forge + ritual overview",
                "holder | history [n]        <dark_gray>holder, original claimant, ownership history",
                "forge [info|tp|generate|rebuild|relocate confirm]",
                "ritual [status|reset [cooldown] confirm|fail confirm]",
                "mobs                        <dark_gray>live ritual mobs + encounter id",
                "cooldowns                   <dark_gray>weapon, retry and exhaustion timers",
                "give <player> confirm       <dark_gray>assign THE blade (never a 2nd copy; old copies are invalidated)",
                "recover <player|altar|here> confirm  <dark_gray>audited re-issue (epoch bump)",
                "verify                      <dark_gray>is the registered item entity alive?",
                "bloodiron give <player> [n] | scatter <radius>",
                "fx <rupture|fury|expire|exhaust|ritual|wave N|reward|fail|announce|ambient|beam>",
                "audit [n] | reload"};
        for (String l : lines) Text.raw(c, "<gray>/oathbreaker <red>" + l);
    }

    // ------------------------------------------------------------------ status

    private static String ts(long ms) {
        return ms <= 0 ? "-" : Instant.ofEpochMilli(ms).toString();
    }

    private void status(CommandSender c) {
        WeaponLedger l = weapons.ledger();
        Text.raw(c, "<dark_red><bold>Oathbreaker");
        Text.raw(c, "<gray>phase <white>" + l.phase + " <gray>epoch <white>" + l.epoch + " <gray>id <dark_gray>" + l.weaponId);
        Text.raw(c, "<gray>original claimant: <red>" + Text.esc(String.valueOf(l.claimantName)) + " <dark_gray>(" + l.claimSource + ", " + ts(l.claimedAtMs) + ")");
        Text.raw(c, "<gray>current holder: <red>" + Text.esc(String.valueOf(l.holderName)) + " <dark_gray>since " + ts(l.holderSinceMs));
        if (l.itemEntityId != null) Text.raw(c, "<gray>item entity <white>" + l.itemEntityId + " <gray>at <white>" + l.dropWorld + String.format(" %.0f %.0f %.0f", l.dropX, l.dropY, l.dropZ));
        forgeInfo(c);
        for (String line : ritual.describe()) Text.raw(c, "<gray>ritual: <white>" + Text.esc(line));
        Text.raw(c, "<gray>persistence healthy: <white>" + store.healthy() + " <gray>active bleeds: <white>" + bleed.count());
    }

    private void forgeInfo(CommandSender c) {
        ForgeState f = forge.state();
        if (!f.present()) {
            Text.raw(c, "<gray>forge: <red>not generated <dark_gray>(busy=" + forge.busy() + ")");
            return;
        }
        Text.raw(c, "<gray>forge: <white>" + f.status + " <gray>at <white>" + f.world + " " + f.centerX + " " + f.floorY + " " + f.centerZ
                + " <gray>radius " + f.radius + " height " + f.height);
        Text.raw(c, "<gray>altar block: <white>" + f.altarX() + " " + f.altarY() + " " + f.altarZ() + " <gray>entrance: <white>" + forge.entranceX() + " "
                + f.surfaceY + " " + forge.entranceZ() + " <gray>(stairway " + f.stairLength + ")");
    }

    private void holder(CommandSender c) {
        WeaponLedger l = weapons.ledger();
        Text.raw(c, "<gray>Original claimant: <red>" + Text.esc(String.valueOf(l.claimantName)) + " <dark_gray>" + l.claimantId);
        Text.raw(c, "<gray>Current holder:    <red>" + Text.esc(String.valueOf(l.holderName)) + " <dark_gray>" + l.holderId + " <gray>phase " + l.phase);
        UUID h = l.holderUuid();
        Player p = h == null ? null : Bukkit.getPlayer(h);
        if (p != null) {
            Location loc = p.getLocation();
            Text.raw(c, "<gray>online at <white>" + loc.getWorld().getName() + String.format(" %.0f %.0f %.0f", loc.getX(), loc.getY(), loc.getZ())
                    + " <gray>rage " + rage.rage(h) + " fury=" + fury.isFury(h));
        }
    }

    private void history(CommandSender c, int n) {
        List<WeaponLedger.Entry> h = weapons.ledger().history;
        for (WeaponLedger.Entry e : h.subList(Math.max(0, h.size() - n), h.size())) {
            Text.raw(c, "<dark_gray>" + ts(e.timeMs) + " <red>" + e.type + " <gray>" + Text.esc(String.valueOf(e.actorName)) + " <dark_gray>" + Text.esc(String.valueOf(e.detail)));
        }
    }

    private void cooldowns(CommandSender c) {
        WeaponLedger l = weapons.ledger();
        long now = System.currentTimeMillis();
        Text.raw(c, "<gray>Rupture ready in <white>" + Math.max(0, (l.ruptureReadyAtMs - now) / 1000) + "s");
        Text.raw(c, "<gray>Last King's Fury ready in <white>" + Math.max(0, (l.ultimateReadyAtMs - now) / 1000) + "s");
        Text.raw(c, "<gray>Ritual retry cooldown: <white>" + ritual.retryRemainingSeconds() + "s");
        for (Map.Entry<String, Long> e : store.state().exhaustionUntilMs.entrySet()) {
            Text.raw(c, "<gray>exhaustion " + e.getKey() + " until " + ts(e.getValue()));
        }
    }

    private void mobs(CommandSender c) {
        Text.raw(c, "<gray>encounter: <white>" + ritual.encounterId() + " <gray>phase " + ritual.phase() + " tracked " + ritual.aliveMobs());
        for (Map.Entry<String, Integer> e : ritual.mobCountsByType().entrySet()) Text.raw(c, "<gray> - " + e.getKey() + ": <white>" + e.getValue());
    }

    // ------------------------------------------------------------------ forge

    private void forgeCmd(CommandSender c, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "info" -> forgeInfo(c);
            case "tp" -> {
                if (!(c instanceof Player p)) {
                    Text.send(c, "Players only.");
                    return;
                }
                Location loc = forge.arenaCentre();
                if (loc == null) {
                    Text.send(c, "<red>No forge.");
                    return;
                }
                forge.withChunkLoaded(loc, () -> p.teleport(loc.clone().add(4, 3, 0)));
                audit.log("ADMIN", p.getName() + " teleported to the Lost Forge");
            }
            case "generate" -> {
                if (!confirmed(a)) {
                    Text.send(c, "<gray>Add <red>confirm<gray> to search for a site and build the forge (only if none exists).");
                    return;
                }
                boolean started = forge.startLocateIfAbsent();
                Text.send(c, started ? "<gray>Searching for a site..." : "<red>A forge already exists or a task is running.");
                audit.log("ADMIN", c.getName() + " requested forge generation: " + started);
            }
            case "rebuild" -> {
                if (!confirmed(a)) {
                    Text.send(c, "<gray>Add <red>confirm<gray> to re-run the blueprint at the same location (repairs damage).");
                    return;
                }
                Text.send(c, forge.rebuild() ? "<gray>Rebuilding..." : "<red>Cannot rebuild now.");
                audit.log("ADMIN", c.getName() + " rebuilt the forge");
            }
            case "relocate" -> {
                if (!confirmed(a)) {
                    Text.send(c, "<red>Relocate forgets the current forge and builds a NEW one elsewhere. The old structure stays in the world. Add confirm.");
                    return;
                }
                if (ritual.active()) {
                    Text.send(c, "<red>A ritual is in progress.");
                    return;
                }
                ForgeState f = forge.state();
                audit.log("ADMIN", c.getName() + " relocated the forge away from " + forge.describeLocation());
                f.status = ForgeState.Status.NONE;
                f.world = null;
                store.saveNow();
                forge.startLocateIfAbsent();
                Text.send(c, "<gray>Searching for a new site...");
            }
            default -> Text.send(c, "<gray>forge [info|tp|generate|rebuild|relocate]");
        }
    }

    // ------------------------------------------------------------------ ritual

    private void ritualCmd(CommandSender c, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "status" -> {
                for (String l : ritual.describe()) Text.raw(c, "<gray>" + Text.esc(l));
            }
            case "reset" -> {
                if (!confirmed(a)) {
                    Text.send(c, "<gray>Add <red>confirm<gray> (and optionally <red>cooldown<gray> before it to also clear the retry cooldown).");
                    return;
                }
                boolean cd = Arrays.stream(a).anyMatch(x -> x.equalsIgnoreCase("cooldown"));
                ritual.adminReset(cd, c.getName());
                Text.send(c, "<gray>Ritual reset.");
            }
            case "fail" -> {
                if (!confirmed(a)) {
                    Text.send(c, "<gray>Add <red>confirm<gray> to fail the running trial.");
                    return;
                }
                ritual.fail("an administrator ended the trial");
                audit.log("ADMIN", c.getName() + " failed the running trial");
            }
            default -> Text.send(c, "<gray>ritual [status|reset|fail]");
        }
    }

    // ------------------------------------------------------------------ weapon management

    private Player target(CommandSender c, String[] a, int idx) {
        if (a.length <= idx) {
            if (c instanceof Player p) return p;
            return null;
        }
        return Bukkit.getPlayerExact(a[idx]);
    }

    private void give(CommandSender c, String[] a) {
        Player t = target(c, a, 1);
        if (t == null || a[1].equalsIgnoreCase("confirm")) {
            Text.send(c, "<gray>Usage: <red>/oathbreaker give <online player> confirm");
            return;
        }
        if (!confirmed(a)) {
            Text.send(c, "<gray>This assigns the ONE blade to <red>" + Text.esc(t.getName()) + "<gray> and invalidates every older copy. Add <red>confirm<gray>.");
            return;
        }
        boolean fresh = !weapons.ledger().exists();
        if (fresh) ritual.adminReset(false, c.getName());
        weapons.adminGive(t, c.getName());
        if (fresh) ritual.onClaimed();
        Text.send(c, "<gray>Oathbreaker now belongs to <red>" + Text.esc(t.getName()) + "<gray> (epoch " + weapons.ledger().epoch + ").");
    }

    private void recover(CommandSender c, String[] a) {
        if (a.length < 2) {
            Text.send(c, "<gray>Usage: <red>/oathbreaker recover <player|altar|here> confirm");
            return;
        }
        if (!weapons.ledger().exists()) {
            Text.send(c, "<red>Nothing to recover: the blade has never been created (complete the ritual or use give).");
            return;
        }
        if (!confirmed(a)) {
            Text.send(c, "<gray>Recovery invalidates every existing copy (epoch bump) and re-issues exactly one. Add <red>confirm<gray>.");
            return;
        }
        String where = a[1].toLowerCase(Locale.ROOT);
        switch (where) {
            case "altar" -> {
                Location altar = forge.altarLocation();
                if (altar == null) {
                    Text.send(c, "<red>No forge.");
                    return;
                }
                forge.withChunkLoaded(altar, () -> weapons.adminDropAt(altar.clone().add(0.5, 1.5, 0.5), c.getName()));
            }
            case "here" -> {
                if (!(c instanceof Player p)) {
                    Text.send(c, "Players only.");
                    return;
                }
                weapons.adminDropAt(p.getLocation(), c.getName());
            }
            default -> {
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    Text.send(c, "<red>Player not online.");
                    return;
                }
                weapons.recoverToHolder(t, c.getName(), "admin recovery");
            }
        }
        Text.send(c, "<gray>Recovered. Epoch is now <white>" + weapons.ledger().epoch + "<gray>.");
    }

    private void verify(CommandSender c) {
        WeaponLedger l = weapons.ledger();
        Text.send(c, "<gray>phase <white>" + l.phase);
        if (l.phase == WeaponLedger.Phase.DROPPED || l.phase == WeaponLedger.Phase.REWARD_PENDING) {
            Entity e = l.itemEntityUuid() == null ? null : Bukkit.getEntity(l.itemEntityUuid());
            Text.send(c, e != null && e.isValid()
                    ? "<green>item entity is alive at " + String.format("%.0f %.0f %.0f", e.getLocation().getX(), e.getLocation().getY(), e.getLocation().getZ())
                    : "<yellow>item entity not found among LOADED entities (its chunk may be unloaded). Use recover only if you know it is lost.");
        } else if (l.phase == WeaponLedger.Phase.HELD) {
            Player p = Bukkit.getPlayer(l.holderUuid());
            if (p == null) Text.send(c, "<gray>holder is offline; item is in their saved inventory.");
            else Text.send(c, "<gray>holder online; carrying " + weapons.claimingSlots(p).size() + " Oathbreaker item(s).");
        }
    }

    // ------------------------------------------------------------------ bloodiron

    private void bloodironCmd(CommandSender c, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "give" -> {
                Player t = target(c, a, 2);
                if (t == null) {
                    Text.send(c, "<red>Usage: /oathbreaker bloodiron give <player> [amount]");
                    return;
                }
                int n = a.length > 3 ? Math.max(1, Math.min(2304, parse(a[3], 64) * (a[3].matches("\\d+") && Integer.parseInt(a[3]) > 200 ? 1 : 1))) : 64;
                if (a.length > 3 && a[3].matches("\\d+")) n = Math.max(1, Math.min(2304, Integer.parseInt(a[3])));
                bloodiron.giveBack(t, n);
                audit.log("ADMIN", c.getName() + " gave " + n + " Bloodiron Ore to " + t.getName());
                Text.send(c, "<gray>Gave <red>" + n + "<gray> authentic Bloodiron Ore to " + Text.esc(t.getName()) + ".");
            }
            case "scatter" -> {
                if (!(c instanceof Player p)) {
                    Text.send(c, "Players only.");
                    return;
                }
                int r = a.length > 2 ? parse(a[2], 3) : 3;
                r = Math.min(r, 8);
                int made = 0, chunks = 0;
                Chunk centre = p.getLocation().getChunk();
                World w = p.getWorld();
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (!w.isChunkLoaded(centre.getX() + dx, centre.getZ() + dz)) continue;
                        made += generator.populate(w.getChunkAt(centre.getX() + dx, centre.getZ() + dz));
                        chunks++;
                    }
                }
                audit.log("ADMIN", c.getName() + " scattered Bloodiron in " + chunks + " loaded chunks (" + made + " blocks)");
                Text.send(c, "<gray>Placed <red>" + made + "<gray> Bloodiron blocks in " + chunks + " loaded, not-yet-populated chunks. <dark_gray>(test aid)");
            }
            case "stats" -> {
                World w = c instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                int chunks = 0, populated = 0, blocks = 0, max = 0, diamonds = 0;
                for (Chunk ch : w.getLoadedChunks()) {
                    chunks++;
                    int n = bloodiron.trackedInChunk(ch);
                    if (n > 0) populated++;
                    blocks += n;
                    max = Math.max(max, n);
                    var snap = ch.getChunkSnapshot(false, false, false);
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = Math.max(w.getMinHeight(), -64); y <= 16; y++) {
                        org.bukkit.Material m = snap.getBlockType(x, y, z);
                        if (m == org.bukkit.Material.DIAMOND_ORE || m == org.bukkit.Material.DEEPSLATE_DIAMOND_ORE) diamonds++;
                    }
                }
                Text.send(c, "<gray>" + w.getName() + ": <white>" + blocks + "<gray> tracked Bloodiron blocks in <white>" + chunks
                        + "<gray> loaded chunks (" + populated + " with ore, max " + max + ", avg " + String.format("%.2f", chunks == 0 ? 0 : blocks / (double) chunks) + "/chunk). Vanilla diamond ore in the same chunks: <white>"
                        + diamonds + " <gray>(" + String.format("%.2f", chunks == 0 ? 0 : diamonds / (double) chunks) + "/chunk)");
            }
            default -> Text.send(c, "<gray>bloodiron [give <player> [n] | scatter <radius> | stats]");
        }
    }

    // ------------------------------------------------------------------ visual tests

    private void fxCmd(CommandSender c, String[] a) {
        if (!(c instanceof Player p)) {
            Text.send(c, "Players only.");
            return;
        }
        String sub = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        Location here = p.getLocation().getBlock().getLocation();
        Location altar = forge.altarLocation() != null && forge.inArena(p.getLocation()) ? forge.altarLocation() : here;
        int radius = forge.state().present() && forge.inArena(p.getLocation()) ? forge.state().radius : 12;
        switch (sub) {
            case "rupture" -> ruptureFx.slash(p, s.ruptureRange, s.ruptureWidth);
            case "hit" -> ruptureFx.hit(p);
            case "bleed" -> {
                ruptureFx.bleedTick(p);
                ruptureFx.bleedIndicator(p);
            }
            case "shield" -> ruptureFx.shieldBlock(p, p);
            case "fury" -> furyFx.activate(p);
            case "expire" -> furyFx.expire(p);
            case "exhaust" -> {
                furyFx.exhaustionStart(p);
                furyFx.exhaustionAura(p);
            }
            case "ritual" -> ritualFx.ritualStart(altar, radius, 120);
            case "wave" -> {
                int w = a.length > 2 ? Math.max(1, Math.min(5, parse(a[2], 1))) : 1;
                ritualFx.waveStart(altar, radius, w, 5);
                Location spawn = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(5));
                ritualFx.mobSpawn(spawn, w);
            }
            case "reward" -> {
                ritualFx.rewardSequence(altar, radius, 120);
                fx.later(120, () -> ritualFx.rewardReveal(altar.clone().add(0.5, 1.5, 0.5)));
            }
            case "fail" -> ritualFx.fail(altar, radius);
            case "announce" -> announce.claim(p);
            case "ambient" -> ritualFx.ambient(altar, radius, 1.0, 0);
            case "beam" -> weaponFx.beam(p.getLocation());
            case "rage" -> {
                weaponFx.rageGain(p, 5, 5, true);
            }
            default -> Text.send(c, "<gray>fx <rupture|hit|bleed|shield|fury|expire|exhaust|ritual|wave N|reward|fail|announce|ambient|beam|rage>");
        }
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] a) {
        if (!sender.hasPermission("kingdomsofash.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(SUBS);
        } else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "forge" -> out.addAll(List.of("info", "tp", "generate", "rebuild", "relocate"));
                case "ritual" -> out.addAll(List.of("status", "reset", "fail"));
                case "bloodiron" -> out.addAll(List.of("give", "scatter", "stats"));
                case "recover" -> {
                    out.addAll(List.of("altar", "here"));
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "give" -> {
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                }
                case "fx" -> out.addAll(List.of("rupture", "hit", "bleed", "shield", "fury", "expire", "exhaust", "ritual", "wave", "reward", "fail", "announce", "ambient", "beam", "rage"));
                default -> {
                }
            }
        } else if (a.length == 3 && a[0].equalsIgnoreCase("bloodiron") && a[1].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        } else {
            out.add("confirm");
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
