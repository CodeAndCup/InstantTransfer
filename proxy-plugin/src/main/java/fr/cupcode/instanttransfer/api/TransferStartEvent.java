package fr.cupcode.instanttransfer.api;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Event fired when an instant transfer starts.
 */
public final class TransferStartEvent {

    private final Player player;
    private final RegisteredServer fromServer;
    private final RegisteredServer toServer;

    public TransferStartEvent(Player player, RegisteredServer fromServer, RegisteredServer toServer) {
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