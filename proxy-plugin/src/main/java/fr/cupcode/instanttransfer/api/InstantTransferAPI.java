package fr.cupcode.instanttransfer.api;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public API for InstantTransfer proxy plugin.
 * Allows other plugins to request instant transfers and listen to transfer events.
 */
public interface InstantTransferAPI {

    /**
     * Result of a transfer request.
     */
    enum TransferResult {
        /** Transfer completed successfully. */
        SUCCESS,
        /** Player is already on the target server. */
        ALREADY_ON_SERVER,
        /** Player is already in a transfer. */
        ALREADY_TRANSFERRING,
        /** Target server not found. */
        SERVER_NOT_FOUND,
        /** Player not found. */
        PLAYER_NOT_FOUND,
        /** Instant transfer is disabled in config. */
        DISABLED,
        /** Internal error occurred. */
        ERROR
    }

    /**
     * Request an instant transfer for a player to a target server.
     * This is equivalent to the player running /transfer <server>.
     *
     * @param player the player to transfer
     * @param target the target server
     * @return a future completing with the transfer result
     */
    CompletableFuture<TransferResult> requestTransfer(Player player, RegisteredServer target);

    /**
     * Request an instant transfer by server name.
     *
     * @param player the player to transfer
     * @param targetServerName the name of the target server
     * @return a future completing with the transfer result
     */
    CompletableFuture<TransferResult> requestTransfer(Player player, String targetServerName);

    /**
     * Check if a player is currently in an instant transfer.
     *
     * @param playerId the player's UUID
     * @return true if the player is being transferred
     */
    boolean isTransferring(UUID playerId);

    /**
     * Check if a player is currently in an instant transfer.
     *
     * @param player the player
     * @return true if the player is being transferred
     */
    default boolean isTransferring(Player player) {
        return isTransferring(player.getUniqueId());
    }

    /**
     * Register a listener for transfer events.
     *
     * @param listener the listener to register
     */
    void registerListener(TransferListener listener);

    /**
     * Unregister a listener.
     *
     * @param listener the listener to unregister
     */
    void unregisterListener(TransferListener listener);
}