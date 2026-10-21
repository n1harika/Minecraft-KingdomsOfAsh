package com.kingdomsofash.combat;

import com.kingdomsofash.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Central, weapon-agnostic router for {@code /ability1} and {@code /ultimate}. Every weapon registers a
 * {@link WeaponAbilities}; the command asks each one whether it is the weapon in the player's main hand and
 * only that weapon ever runs, so one weapon can never trigger another weapon's ability.
 */
public final class AbilityDispatcher {
    /** Implemented once per weapon. */
    public interface WeaponAbilities {
        /** True if the player's main hand holds THIS weapon (and the player may use it). */
        boolean handles(Player p);

        void ability1(Player p);

        void ultimate(Player p);
    }

    private final Plugin plugin;
    private final List<WeaponAbilities> providers = new ArrayList<>();

    public AbilityDispatcher(Plugin plugin) {
        this.plugin = plugin;
    }

    public void register(WeaponAbilities w) {
        providers.add(w);
    }

    public void dispatch(Player p, boolean ultimate) {
        for (WeaponAbilities w : providers) {
            if (w.handles(p)) {
                if (ultimate) w.ultimate(p);
                else w.ability1(p);
                return;
            }
        }
        Text.send(p, "<gray>The weapon in your hand has no " + (ultimate ? "ultimate" : "first ability") + ".");
    }

    /** Registers extra alias labels (config) through the command map; reports conflicts instead of stealing labels. */
    public void registerAliases(List<String> labels, boolean ultimate) {
        CommandMap map = Bukkit.getCommandMap();
        for (String label : labels) {
            String l = label.toLowerCase().replace("/", "").trim();
            if (l.isEmpty()) continue;
            Command c = new Command(l, "Weapon " + (ultimate ? "ultimate" : "ability 1") + " (alias)", "/" + l, List.of()) {
                @Override
                public boolean execute(@NotNull CommandSender sender, @NotNull String commandLabel, @NotNull String[] args) {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage("Players only.");
                        return true;
                    }
                    if (!p.hasPermission("kingdomsofash.use")) {
                        p.sendMessage(Text.mm("<red>No permission."));
                        return true;
                    }
                    dispatch(p, ultimate);
                    return true;
                }
            };
            boolean ok = map.register("kingdomsofash", c);
            if (!ok) {
                plugin.getLogger().warning("Alias /" + l + " is already taken by another plugin; use /kingdomsofash:" + l
                        + " or choose another alias in config.yml");
            }
        }
    }
}
