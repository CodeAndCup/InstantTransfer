package fr.cupcode.instanttransfer.switching;

import fr.cupcode.instanttransfer.config.PluginConfig;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
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
    private final Map<UUID, SwitchState> states = new ConcurrentHashMap<>();

    public SwitchManager(PluginConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public SwitchState getOrCreate(UUID uuid) {
        return states.computeIfAbsent(uuid, k -> new SwitchState());
    }

    public SwitchState get(UUID uuid) {
        return states.get(uuid);
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
        SwitchState state = getOrCreate(player.getUniqueId());
        // Snapshot the current (old server's) entities and chunks; they become ghosts after the switch.
        state.snapshotGhosts();
        // Arm the arrival guard: freeze on the arrival teleport, release on the landing chunk.
        state.armGuard(System.currentTimeMillis());
        if (config.debug()) {
            String from = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("?");
            String to = event.getOriginalServer().getServerInfo().getName();
            logger.info("[InstantTransfer] switch armed for {}: {} -> {} (snapshot {} entities, {} chunks)",
                    player.getUsername(), from, to,
                    state.ghostEntities().size(), state.ghostChunks().size());
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
