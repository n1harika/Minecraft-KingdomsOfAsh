package com.kingdomsofash.ore;

import com.kingdomsofash.config.Settings;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Generation hook, Silk-Touch-only collection, and anti-duplication rules for Bloodiron. */
public final class OreListener implements Listener {
    private final Plugin plugin;
    private final Settings s;
    private final BloodironService ore;
    private final OreGenerator generator;

    public OreListener(Plugin plugin, Settings s, BloodironService ore) {
        this.plugin = plugin;
        this.s = s;
        this.ore = ore;
        this.generator = new OreGenerator(s, ore);
    }

    public OreGenerator generator() {
        return generator;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!s.bloodironEnabled || !e.isNewChunk()) return;
        if (!s.bloodironWorlds.contains(e.getWorld().getName())) return;
        try {
            generator.populate(e.getChunk());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Bloodiron generation failed for chunk " + e.getChunk().getX() + "," + e.getChunk().getZ() + ": " + ex);
        }
    }

    // ------------------------------------------------------------------ mining

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != s.bloodironHost || !ore.isTracked(b)) return;
        Player p = e.getPlayer();
        ore.untrack(b);
        e.setDropItems(false);
        ItemStack tool = p.getInventory().getItemInMainHand();
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        if (!b.isPreferredTool(tool) && p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            e.setExpToDrop(0);
            return; // wrong tool: nothing, exactly like ordinary ore
        }
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) {
            e.setExpToDrop(0);
            return; // creative breaking never produces items (no free ritual material)
        }
        boolean pickaxe = tool.getType().name().endsWith("_PICKAXE");
        if (pickaxe && tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            b.getWorld().dropItemNaturally(at, ore.create(1));
            e.setExpToDrop(0);
            return;
        }
        // ordinary mining: never the ore block itself
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int n = s.oreDropMin + (s.oreDropMax > s.oreDropMin ? r.nextInt(s.oreDropMax - s.oreDropMin + 1) : 0);
        if (n > 0 && s.oreDropMaterial != Material.AIR) b.getWorld().dropItemNaturally(at, new ItemStack(s.oreDropMaterial, n));
        e.setExpToDrop(s.oreXpMin + (s.oreXpMax > s.oreXpMin ? r.nextInt(s.oreXpMax - s.oreXpMin + 1) : 0));
    }

    // ------------------------------------------------------------------ duplication guards

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack in = e.getItemInHand();
        if (!ore.claims(in)) return;
        if (!ore.isAuthentic(in) || !s.allowPlacingOreItem) {
            e.setCancelled(true);
            return;
        }
        // placing is allowed by config: the placed block becomes a tracked Bloodiron block again
        ore.track(e.getBlockPlaced());
    }

    private void handleExplosion(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (b.getType() == s.bloodironHost && ore.isTracked(b)) {
                ore.untrack(b);
                it.remove();
                b.setType(Material.AIR, false); // gone, and no vanilla loot (would be free nuggets)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        handleExplosion(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        handleExplosion(e.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (b.getType() == s.bloodironHost && ore.isTracked(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (b.getType() == s.bloodironHost && ore.isTracked(b)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent e) {
        if (ore.claims(e.getSource())) e.setCancelled(true);
    }
}
