package com.kingdomsofash.combat;

import com.kingdomsofash.config.Settings;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Blackened spectral armor with crimson runes, shown WITHOUT touching the wearer's real equipment.
 *
 * <p>Mechanism: {@code Player#sendEquipmentChange} sends client-side-only equipment packets about the wearer to
 * each viewer. The server's real inventory is never modified. Vanilla re-broadcasts the true equipment whenever
 * it changes, so a light periodic refresh (and an armor-change hook) re-applies the illusion; when Fury ends the
 * true equipment is re-sent. Two looks exist:</p>
 * <ul>
 *   <li><b>PACK</b>: an {@code equippable} component whose asset id {@code kingdomsofash:spectral_king} is
 *       defined by the bundled resource pack (black plate, crimson runes).</li>
 *   <li><b>VANILLA</b> fallback: netherite armor with a redstone-coloured trim.</li>
 * </ul>
 * In AUTO mode each viewer gets the PACK look only if their client reported a successfully loaded pack.
 * First-person view is unaffected (the client never renders your own armor in first person). By default the
 * wearer's own third-person view is NOT modified, because sending equipment about yourself also rewrites your
 * client-side armor slots until the next inventory sync; enable {@code show-to-self} to accept that trade-off.
 */
public final class SpectralArmor {
    private static final Key PACK_ASSET = Key.key("kingdomsofash", "spectral_king");
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private final Plugin plugin;
    private final Settings s;
    private final Set<UUID> wearers = new HashSet<>();
    private final Set<UUID> packLoaded = new HashSet<>();
    private final Map<EquipmentSlot, ItemStack> vanilla = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, ItemStack> pack = new EnumMap<>(EquipmentSlot.class);
    private BukkitTask task;

    public SpectralArmor(Plugin plugin, Settings s) {
        this.plugin = plugin;
        this.s = s;
        buildItems();
    }

    private void buildItems() {
        Material[] mats = {Material.NETHERITE_HELMET, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS};
        for (int i = 0; i < 4; i++) {
            ItemStack v = new ItemStack(mats[i]);
            if (v.getItemMeta() instanceof ArmorMeta am) {
                am.setTrim(new ArmorTrim(TrimMaterial.REDSTONE, i % 2 == 0 ? TrimPattern.RIB : TrimPattern.SILENCE));
                am.addEnchant(Enchantment.UNBREAKING, 1, true); // glint
                am.setEnchantmentGlintOverride(true);
                v.setItemMeta(am);
            }
            vanilla.put(SLOTS[i], v);

            ItemStack p = new ItemStack(mats[i]);
            p.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(SLOTS[i]).assetId(PACK_ASSET).build());
            pack.put(SLOTS[i], p);
        }
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, s.armorRefreshTicks, s.armorRefreshTicks);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (UUID id : new HashSet<>(wearers)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) hide(p);
        }
        wearers.clear();
    }

    public void packLoaded(UUID viewer, boolean loaded) {
        if (loaded) packLoaded.add(viewer);
        else packLoaded.remove(viewer);
    }

    public void forget(UUID viewer) {
        packLoaded.remove(viewer);
    }

    public boolean isWearing(UUID id) {
        return wearers.contains(id);
    }

    private Map<EquipmentSlot, ItemStack> lookFor(Player viewer) {
        return switch (s.armorMode) {
            case "PACK" -> pack;
            case "VANILLA" -> vanilla;
            default -> packLoaded.contains(viewer.getUniqueId()) ? pack : vanilla;
        };
    }

    public void show(Player wearer) {
        if (!s.armorEnabled) return;
        wearers.add(wearer.getUniqueId());
        send(wearer);
    }

    public void refresh(Player wearer) {
        if (wearers.contains(wearer.getUniqueId())) send(wearer);
    }

    private void refreshAll() {
        for (UUID id : wearers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) send(p);
        }
    }

    private void send(Player wearer) {
        double r2 = s.armorViewerDistance * s.armorViewerDistance;
        for (Player viewer : wearer.getWorld().getPlayers()) {
            if (viewer == wearer && !s.armorShowToSelf) continue;
            if (viewer.getLocation().distanceSquared(wearer.getLocation()) > r2) continue;
            viewer.sendEquipmentChange(wearer, lookFor(viewer));
        }
    }

    /** Re-sends the true equipment to every viewer and stops refreshing. */
    public void hide(Player wearer) {
        if (!wearers.remove(wearer.getUniqueId())) return;
        PlayerInventory inv = wearer.getInventory();
        Map<EquipmentSlot, ItemStack> real = new EnumMap<>(EquipmentSlot.class);
        real.put(EquipmentSlot.HEAD, inv.getHelmet());
        real.put(EquipmentSlot.CHEST, inv.getChestplate());
        real.put(EquipmentSlot.LEGS, inv.getLeggings());
        real.put(EquipmentSlot.FEET, inv.getBoots());
        for (Player viewer : wearer.getWorld().getPlayers()) {
            if (viewer == wearer && !s.armorShowToSelf) continue;
            viewer.sendEquipmentChange(wearer, real);
        }
    }
}
