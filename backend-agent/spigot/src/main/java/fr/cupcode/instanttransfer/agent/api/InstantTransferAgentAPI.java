package fr.cupcode.instanttransfer.agent.api;

import java.util.UUID;

/**
 * API for other plugins to interact with the InstantTransferAgent.
 */
public interface InstantTransferAgentAPI {

    void onPlayerArrive(UUID playerId, String fromServer);
    void grantArrivalFlight(UUID playerId, int ticks);
    void revokeArrivalFlight(UUID playerId);
    boolean hasArrivalFlight(UUID playerId);

    void addFallGrace(UUID uuid);
    void removeFallGrace(UUID uuid);
    boolean hasFallGrace(UUID uuid);

    boolean isDebug();
}