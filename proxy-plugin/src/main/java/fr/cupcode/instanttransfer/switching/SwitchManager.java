package fr.cupcode.instanttransfer.switching;

import fr.cupcode.instanttransfer.InstantTransfer;
import fr.cupcode.instanttransfer.config.PluginConfig;
import fr.cupcode.instanttransfer.switching.SwitchState;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a backend-to-backend switch and arms the per-player {@link SwitchState}: it snapshots the
 * previous server's entities/chunks (to remove as ghosts) and arms the arrival guard, so that
 * {@code SeamlessPacketListener} freezes the player until the landing chunk has arrived.
 */
public final class SwitchManager {

    private final PluginConfig config;
    private final Logger logger;
    private final InstantTransfer plugin;
    private final Map<UUID, SwitchState> states = new ConcurrentHashMap<>();
    private final Map<UUID, RegisteredServer> previousServers = new ConcurrentHashMap<>();

    public SwitchManager(PluginConfig config, Logger logger, InstantTransfer plugin) {
        this.config = config;
        this.logger = logger;
        this.plugin = plugin;
    }

    public SwitchState getOrCreate(UUID uuid) {
        return states.computeIfAbsent(uuid, k -> new SwitchState());
    }

    public SwitchState get(UUID uuid) {
        return states.get(uuid);
    }

    public RegisteredServer getPreviousServer(UUID uuid) {
        return previousServers.get(uuid);
    }

    public RegisteredServer getCurrentServer(UUID uuid) {
        Player player = plugin.getProxy().getPlayer(uuid).orElse(null);
        if (player != null) {
            return player.getCurrentServer()
                    .map(ServerConnection::getServerInfo)
                    .flatMap(info -> plugin.getProxy().getServer(info.getName()))
                    .orElse(null);
        }
        return null;
    }

    private RegisteredServer toRegisteredServer(ServerInfo serverInfo) {
        return plugin.getProxy().getServer(serverInfo.getName()).orElse(null);
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent event) {
        if (!config.seamlessEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getCurrentServer().isEmpty()) {
            return; // initial join, not a switch
        }

        // Check whether this switch came from /transfer or the API (drives event firing only).
        UUID uuid = player.getUniqueId();
        boolean instantTransferRequest = plugin.consumeInstantTransferRequest(uuid);
        if (!instantTransferRequest && config.debug()) {
            logger.debug("[InstantTransfer] Normal server switch for {} (no instant transfer flag)"
                    + " — seamless machinery arms anyway", player.getUsername());
        }

        // Verify the target server matches what was requested (extra safety)
        RegisteredServer requestedTarget = event.getOriginalServer();
        if (config.debug()) {
            logger.debug("[InstantTransfer] Instant transfer armed for {} to {}", player.getUsername(), requestedTarget.getServerInfo().getName());
        }

        // Store the previous server for transfer events
        ServerConnection currentConnection = player.getCurrentServer().orElseThrow();
        RegisteredServer previousServer = toRegisteredServer(currentConnection.getServerInfo());
        if (previousServer != null) {
            previousServers.put(uuid, previousServer);
        }

        SwitchState state = getOrCreate(uuid);
        // Snapshot the current (old server's) entities and chunks; they become ghosts after the
        // switch. Runs for EVERY post-join switch — a plain /server switch is seamless too (the
        // patch handles it unconditionally), and without this snapshot the ghost/purge lifecycle
        // never engages (observed live: S1 entities surviving on S2).
        state.snapshotGhosts();
        // Arm the arrival guard: freeze on the arrival teleport, release on the landing chunk.
        state.armGuard(System.currentTimeMillis());
        // Re-enable the raw-peek sanity probes for this switch (they stop after 10 packets to
        // stay free in steady state).
        state.resetPeekProbes();

        // Fire transfer start event only for explicit instant-transfer requests
        if (instantTransferRequest) {
            plugin.fireTransferStart(
                    player,
                    previousServer,
                    requestedTarget
            );
        }

        if (config.debug()) {
            String from = currentConnection.getServerInfo().getName();
            String to = requestedTarget.getServerInfo().getName();
            logger.info("[InstantTransfer] switch armed for {}: {} -> {} (snapshot {} entities, {} chunks)",
                    player.getUsername(), from, to,
                    state.ghostEntities().size(), state.ghostChunks().size());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        states.remove(event.getPlayer().getUniqueId());
        previousServers.remove(event.getPlayer().getUniqueId());
    }
}