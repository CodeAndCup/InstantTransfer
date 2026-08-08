package fr.cupcode.instanttransfer.api;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Event fired when an instant transfer times out (arrival chunk not received in time).
 */
public final class TransferTimeoutEvent {

    private final Player player;
    private final RegisteredServer fromServer;
    private final RegisteredServer toServer;

    public TransferTimeoutEvent(Player player, RegisteredServer fromServer, RegisteredServer toServer) {
        this.player = player;
        this.fromServer = fromServer;
        this.toServer = toServer;
    }

    public Player getPlayer() {
        return player;
    }

    public RegisteredServer getFromServer() {
        return fromServer;
    }

    public RegisteredServer getToServer() {
        return toServer;
    }
}