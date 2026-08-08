package fr.cupcode.instanttransfer.agent.api;

import java.util.UUID;

/**
 * Public API for InstantTransferAgent backend plugin.
 * Allows other plugins on the backend to interact with the arrival system.
 */
public interface InstantTransferAgentAPI {

    /**
     * Called when a player arrives via instant transfer.
     * Other plugins can listen to this to perform custom actions on arrival.
     *
     * @param playerId the player's UUID
     * @param fromServer the server the player came from
     */
    void onPlayerArrive(UUID playerId, String fromServer);

    /**
     * Grant temporary flight permission to a player for arrival handling.
     * This is automatically called by the agent when a player arrives via instant transfer.
     *
     * @param playerId the player's UUID
     * @param ticks duration in ticks (1 tick = 50ms)
     */
    void grantArrivalFlight(UUID playerId, int ticks);

    /**
     * Revoke the temporary arrival flight permission immediately.
     * Called by the agent when the proxy signals unfreeze.
     *
     * @param playerId the player's UUID
     */
    void revokeArrivalFlight(UUID playerId);

    /**
     * Check if a player currently has arrival flight granted.
     *
     * @param playerId the player's UUID
     * @return true if the player has arrival flight
     */
    boolean hasArrivalFlight(UUID playerId);

    /**
     * Register a listener for agent events.
     *
     * @param listener the listener to register
     */
    void registerListener(AgentListener listener);

    /**
     * Unregister a listener.
     *
     * @param listener the listener to unregister
     */
    void unregisterListener(AgentListener listener);

    /**
     * Listener for agent events.
     */
    interface AgentListener {
        /**
         * Called when a player arrives via instant transfer.
         *
         * @param event the arrival event
         */
        void onPlayerArrive(ArrivalEvent event);

        /**
         * Called when arrival flight is granted.
         *
         * @param event the flight grant event
         */
        default void onFlightGranted(FlightEvent event) {}

        /**
         * Called when arrival flight is revoked.
         *
         * @param event the flight revoke event
         */
        default void onFlightRevoked(FlightEvent event) {}
    }

    /**
     * Event fired when a player arrives via instant transfer.
     */
    class ArrivalEvent {
        private final UUID playerId;
        private final String fromServer;

        public ArrivalEvent(UUID playerId, String fromServer) {
            this.playerId = playerId;
            this.fromServer = fromServer;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public String getFromServer() {
            return fromServer;
        }
    }

    /**
     * Event fired when arrival flight is granted or revoked.
     */
    class FlightEvent {
        private final UUID playerId;
        private final int ticks;

        public FlightEvent(UUID playerId, int ticks) {
            this.playerId = playerId;
            this.ticks = ticks;
        }

        public UUID getPlayerId() {
            return playerId;
        }

        public int getTicks() {
            return ticks;
        }
    }
}