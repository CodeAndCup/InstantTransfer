package fr.cupcode.instanttransfer.agent;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;

import java.util.logging.Logger;

/**
 * Backend-side (server → proxy) view of the switch handshake. When the proxy connects a player to
 * THIS backend during a switch, the server drives a login → configuration → play sequence with the
 * proxy (which relays it to the client). This listener logs that handshake from the SERVER's side,
 * so we can see exactly what the backend sends and what responses it expects — the missing half of
 * the picture needed to design a coordinated "skip configuration" switch.
 *
 * <p>Observation only: it cancels nothing.</p>
 */
public final class AgentPacketListener implements PacketListener {

    private final Logger logger;

    public AgentPacketListener(Logger logger) {
        this.logger = logger;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        log("OUT", event.getUser(), event.getConnectionState(), event.getPacketType());
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        log("IN ", event.getUser(), event.getConnectionState(), event.getPacketType());
    }

    private void log(String dir, User user, ConnectionState state, PacketTypeCommon type) {
        // Only the config handshake and the play-entry markers matter here; skip the PLAY firehose.
        boolean interesting = state == ConnectionState.CONFIGURATION
                || type == PacketType.Play.Server.JOIN_GAME
                || type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK
                || type == PacketType.Play.Client.CONFIGURATION_ACK;
        if (!interesting) {
            return;
        }
        String name = user == null ? "?" : (user.getName() != null ? user.getName() : String.valueOf(user.getUUID()));
        logger.info(String.format("[DIAG-BACKEND] %s %s state=%s pkt=%s", dir, name, state, type));
    }
}
