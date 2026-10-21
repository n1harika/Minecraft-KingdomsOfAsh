package com.kingdomsofash.weapon;

import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import com.kingdomsofash.config.Settings;
import com.kingdomsofash.fx.AnnounceFx;
import com.kingdomsofash.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Enforces the curse: Oathbreaker stays in the holder's own inventory, drops only on death (or combat-log),
 * transfers only by pick-up, and can never be stored, hidden, thrown away or duplicated.
 */
public final class WeaponFlowListener implements Listener {
    private final Plugin plugin;
    private final Settings s;
    private final WeaponService weapons;
    private final AnnounceFx announce;

    public WeaponFlowListener(Plugin plugin, Settings s, WeaponService weapons, AnnounceFx announce) {
        this.plugin = plugin;
        this.s = s;
        this.weapons = weapons;
        this.announce = announce;
    }

    private boolean claims(ItemStack st) {
        return weapons.items().claimsToBeOathbreaker(st);
    }

    // ------------------------------------------------------------------ pick-up

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickupValidate(EntityPickupItemEvent e) {
        Item item = e.getItem();
        if (!claims(item.getItemStack())) return;
        if (!(e.getEntity() instanceof Player p)) {
            e.setCancelled(true); // allays, villagers, zombies ... never
            return;
        }
        WeaponService.PickupVerdict v = weapons.validatePickup(p, item);
        if (v == WeaponService.PickupVerdict.DENY_REMOVE) {
            e.setCancelled(true);
            weapons.removeEntityQuietly(item);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAttemptPickup(PlayerAttemptPickupItemEvent e) {
        Item item = e.getItem();
        if (!claims(item.getItemStack())) return;
        GameMode gm = e.getPlayer().getGameMode();
        if (gm == GameMode.SPECTATOR) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickupCommit(EntityPickupItemEvent e) {
        Item item = e.getItem();
        if (!claims(item.getItemStack()) || !(e.getEntity() instanceof Player p)) return;
        WeaponService.PickupVerdict v = weapons.validatePickup(p, item);
        if (v == WeaponService.PickupVerdict.ALLOW_CLAIM || v == WeaponService.PickupVerdict.ALLOW_TRANSFER) {
            weapons.commitPickup(p, v, item);
        }
    }

    // ------------------------------------------------------------------ drops & spawns

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVoluntaryDrop(PlayerDropItemEvent e) {
        ItemStack st = e.getItemDrop().getItemStack();
        if (!claims(st)) return;
        Player p = e.getPlayer();
        if (p.isDead() || weapons.isSanctioned(p.getUniqueId())) return;
        e.setCancelled(true);
        Text.send(p, "<dark_red>The curse binds Oathbreaker to you. <gray>You cannot cast it away.");
        weapons.fxHint(p);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemSpawnGate(ItemSpawnEvent e) {
        ItemStack st = e.getEntity().getItemStack();
        if (!claims(st) || weapons.internalSpawn()) return;
        if (!weapons.isLegit(st)) {
            e.setCancelled(true); // forged or stale copy never enters the world
            weapons.audit("INTEGRITY", "blocked spawn of a forged/stale Oathbreaker item entity at " + WeaponService.fmt(e.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        Item it = e.getEntity();
        ItemStack st = it.getItemStack();
        if (!claims(st) || weapons.internalSpawn() || !weapons.isLegit(st)) return;
        boolean planned = weapons.ledger().holderUuid() != null && weapons.isSanctioned(weapons.ledger().holderUuid());
        weapons.registerDroppedEntity(it, planned ? "death or disconnect of the holder" : "unplanned drop");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer();
        boolean wasHolder = weapons.ledger().isHeldBy(victim.getUniqueId());
        weapons.onHolderDeath(victim, e.getDrops(), e.getKeepInventory());
        if (wasHolder && s.broadcastOwnershipChange) {
            Bukkit.getScheduler().runTask(plugin, () -> announce.fallen(victim.getName(), victim.getLocation(), s.revealDropCoordinates));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (s.combatLogSeconds > 0 && weapons.ledger().isHeldBy(p.getUniqueId()) && weapons.recentlyInCombat(p.getUniqueId())) {
            weapons.dropForCombatLog(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) weapons.purgeIllegitimate(p);
        }, 5L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (d instanceof Player && d != victim) weapons.noteStrike(victim);
    }

    // ------------------------------------------------------------------ removal (void etc.)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent e) {
        if (!(e.getEntity() instanceof Item it)) return;
        if (!claims(it.getItemStack())) return;
        if (weapons.wasOurRemoval(it.getUniqueId())) return;
        switch (e.getCause()) {
            case OUT_OF_WORLD, DESPAWN, DEATH, EXPLODE, HIT -> {
                if (weapons.isLegit(it.getItemStack())) weapons.respawnLostEntity(it.getUniqueId(), it.getLocation());
            }
            default -> {
                // PICKUP, UNLOAD, DROP, ... are normal
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) {
        if (e.getEntity() instanceof Item it && claims(it.getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        if (claims(e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent e) {
        if (claims(e.getItem())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ inventory restrictions

    private boolean isForeignTop(InventoryView view) {
        Inventory top = view.getTopInventory();
        return top.getType() != InventoryType.CRAFTING; // the player's own 2x2 crafting view has no storage
    }

    private static boolean topIsClicked(InventoryClickEvent e) {
        Inventory clicked = e.getClickedInventory();
        return clicked != null && clicked.equals(e.getView().getTopInventory());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        ItemStack cursor = e.getCursor();
        ItemStack current = e.getCurrentItem();
        boolean cursorOath = claims(cursor);
        boolean currentOath = claims(current);
        ClickType click = e.getClick();

        // number-key / offhand swaps pull a *hotbar* item across
        boolean hotbarOath = false;
        if (click == ClickType.NUMBER_KEY) {
            hotbarOath = claims(e.getWhoClicked().getInventory().getItem(e.getHotbarButton()));
        } else if (click == ClickType.SWAP_OFFHAND) {
            hotbarOath = claims(e.getWhoClicked().getInventory().getItemInOffHand());
        }
        if (!cursorOath && !currentOath && !hotbarOath) return;

        boolean top = topIsClicked(e);
        InventoryAction a = e.getAction();
        boolean deny = false;

        if (top) {
            // anything that would move the sword INTO the top inventory
            deny = switch (a) {
                case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> cursorOath;
                case HOTBAR_SWAP -> hotbarOath;
                default -> false;
            };
            if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) deny |= hotbarOath;
        } else {
            // shift-click from the player's inventory into an open container
            if (a == InventoryAction.MOVE_TO_OTHER_INVENTORY && currentOath && isForeignTop(e.getView())) deny = true;
        }
        // dropping out of the window / with the drop key
        if (a == InventoryAction.DROP_ALL_SLOT || a == InventoryAction.DROP_ONE_SLOT) deny |= currentOath;
        if (a == InventoryAction.DROP_ALL_CURSOR || a == InventoryAction.DROP_ONE_CURSOR) deny |= cursorOath;
        if (e.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE && cursorOath) deny = true;
        // the crafting grid of the player's own view counts as "top" and is rejected above (CRAFTING raw slots 0-4)

        if (deny) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
                Text.send(p, "<dark_red>Oathbreaker must stay with you. <gray>It cannot be stored or discarded.");
                weapons.fxHint(p);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!claims(e.getOldCursor()) && !claims(e.getCursor())) return;
        int topSize = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent e) {
        if (claims(e.getCursor()) && e.getSlotType() == org.bukkit.event.inventory.InventoryType.SlotType.OUTSIDE) {
            e.setCancelled(true); // creative "throw away"
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.PLAYER) return;
        for (int i = 0; i < top.getSize(); i++) {
            ItemStack st = top.getItem(i);
            if (claims(st)) {
                top.setItem(i, null);
                weapons.audit("INTEGRITY", "removed an Oathbreaker found in a " + top.getType() + " opened by " + e.getPlayer().getName());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFrame(PlayerInteractEntityEvent e) {
        Entity target = e.getRightClicked();
        boolean storage = target instanceof ItemFrame || target instanceof org.bukkit.entity.ArmorStand
                || target instanceof org.bukkit.entity.Allay;
        if (!storage) return;
        if (claims(e.getPlayer().getInventory().getItemInMainHand()) || claims(e.getPlayer().getInventory().getItemInOffHand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        ItemStack st = p.getInventory().getItem(e.getNewSlot());
        if (claims(st) && !(weapons.isLegit(st) && weapons.ledger().isHeldBy(p.getUniqueId()))) {
            p.getInventory().setItem(e.getNewSlot(), null);
            weapons.audit("INTEGRITY", "removed an illegitimate Oathbreaker from " + p.getName() + " on hotbar select");
        }
    }
}
