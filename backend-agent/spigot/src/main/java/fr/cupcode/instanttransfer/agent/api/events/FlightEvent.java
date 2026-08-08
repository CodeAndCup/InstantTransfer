package fr.cupcode.instanttransfer.agent.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class FlightEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final int ticks;

    public FlightEvent(UUID playerId, int ticks) {
        this.playerId = playerId;
        this.ticks = ticks;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getTicks() {
        return ticks;
    }
}
