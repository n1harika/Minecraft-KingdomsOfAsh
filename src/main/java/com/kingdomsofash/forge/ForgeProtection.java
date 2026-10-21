package com.kingdomsofash.forge;

import com.kingdomsofash.config.Settings;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.Plugin;

/**
 * Terrain protection for the Lost Forge. Only STRUCTURE changes are blocked: combat damage, abilities, projectiles
 * and item use inside the arena are untouched, and explosions still hurt entities - they just stop eating blocks.
 * Players holding {@code kingdomsofash.bypass.protection} (default: nobody) can edit the structure.
 */
public final class ForgeProtection implements Listener {
    private final Plugin plugin;
    private final Settings s;
    private final ForgeService forge;

    public ForgeProtection(Plugin plugin, Settings s, ForgeService forge) {
        this.plugin = plugin;
        this.s = s;
        this.forge = forge;
    }

    private boolean prot(Block b) {
        return s.protTerrain && forge.state().present() && forge.isProtected(b);
    }

    private boolean bypass(Player p) {
        return p != null && p.hasPermission("kingdomsofash.bypass.protection");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (s.protBlocks && prot(e.getBlock()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (s.protBlocks && (prot(e.getBlock()) || prot(e.getBlockAgainst())) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (s.protFluids && prot(e.getBlock()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (s.protFluids && prot(e.getBlock()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!s.protFluids || !(e.getBlock().getBlockData() instanceof Directional d)) return;
        Material m = e.getItem().getType();
        if (m == Material.WATER_BUCKET || m == Material.LAVA_BUCKET || m == Material.FLINT_AND_STEEL || m == Material.FIRE_CHARGE
                || m == Material.BONE_MEAL || m == Material.BUCKET) {
            if (prot(e.getBlock().getRelative(d.getFacing()))) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (s.protExplosions) e.blockList().removeIf(this::prot);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (s.protExplosions) e.blockList().removeIf(this::prot);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!s.protPistons) return;
        BlockFace f = e.getDirection();
        if (prot(e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        for (Block b : e.getBlocks()) {
            if (prot(b) || prot(b.getRelative(f))) {
                e.setCancelled(true);
                return;
            }
        }
        if (prot(e.getBlock().getRelative(f))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!s.protPistons) return;
        for (Block b : e.getBlocks()) {
            if (prot(b) || prot(b.getRelative(e.getDirection()))) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (s.protMobGriefing && prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent e) {
        if (s.protFluids && prot(e.getToBlock()) && !prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (s.protFire && prot(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (s.protFire && prot(e.getBlock()) && !bypass(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        if (s.protFire && prot(e.getBlock())) e.setCancelled(true);
    }

    /** Furnaces, anvils, bells, candles... inside the hall are scenery. Item use (bows, pearls, food) still works. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (!s.protBlocks || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!forge.state().present() || !forge.isProtected(b) || bypass(e.getPlayer())) return;
        if (forge.inArena(b.getLocation().add(0.5, 0.5, 0.5))) e.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!s.protNaturalSpawns || !forge.ready()) return;
        switch (e.getSpawnReason()) {
            case CUSTOM, COMMAND, SPAWNER_EGG, DEFAULT -> {
            }
            default -> {
                if (forge.inArena(e.getLocation())) e.setCancelled(true);
            }
        }
    }
}
