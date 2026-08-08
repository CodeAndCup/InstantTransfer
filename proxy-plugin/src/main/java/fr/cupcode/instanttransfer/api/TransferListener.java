package fr.cupcode.instanttransfer.api;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

/**
 * Listener for instant transfer events.
 */
public interface TransferListener {

    /**
     * Called when an instant transfer starts.
     *
     * @param event the transfer start event
     */
    void onTransferStart(TransferStartEvent event);

    /**
     * Called when an instant transfer completes (successfully or not).
     *
     * @param event the transfer complete event
     */
    void onTransferComplete(TransferCompleteEvent event);

    /**
     * Called when an instant transfer times out (arrival chunk not received in time).
     *
     * @param event the transfer timeout event
     */
    default void onTransferTimeout(TransferTimeoutEvent event) {
        // Default empty implementation
    }
}