package fr.cupcode.instanttransfer.agent.api.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class ArrivalEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String fromServer;

    public ArrivalEvent(UUID playerId, String fromServer) {
        this.playerId = playerId;
        this.fromServer = fromServer;
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

    public String getFromServer() {
        return fromServer;
    }
}
