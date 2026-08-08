package fr.cupcode.instanttransfer.api;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Event fired when an instant transfer completes.
 */
public final class TransferCompleteEvent {

    private final Player player;
    private final RegisteredServer fromServer;
    private final RegisteredServer toServer;
    private final boolean success;
    private final String reason;

    public TransferCompleteEvent(Player player, RegisteredServer fromServer, RegisteredServer toServer,
                                 boolean success, String reason) {
        this.player = player;
        this.fromServer = fromServer;
        this.toServer = toServer;
        this.success = success;
        this.reason = reason;
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

    public boolean isSuccess() {
        return success;
    }

    public String getReason() {
        return reason;
    }
}