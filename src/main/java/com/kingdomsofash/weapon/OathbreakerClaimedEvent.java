package com.kingdomsofash.weapon;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired once, on the server thread, after Oathbreaker has been claimed for the first time. */
public final class OathbreakerClaimedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player claimant;

    public OathbreakerClaimedEvent(Player claimant) {
        this.claimant = claimant;
    }

    public Player claimant() {
        return claimant;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
