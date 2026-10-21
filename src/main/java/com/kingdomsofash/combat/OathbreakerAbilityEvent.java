package com.kingdomsofash.combat;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Fired just before an ability is committed. Cancelling it (region / safe-zone plugins, minigames) means the
 * activation is treated as invalid: no Rage is spent and no cooldown starts.
 */
public final class OathbreakerAbilityEvent extends Event implements Cancellable {
    public enum Ability { RUPTURE, LAST_KINGS_FURY }

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Ability ability;
    private boolean cancelled;

    public OathbreakerAbilityEvent(Player player, Ability ability) {
        this.player = player;
        this.ability = ability;
    }

    public Player player() {
        return player;
    }

    public Ability ability() {
        return ability;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
