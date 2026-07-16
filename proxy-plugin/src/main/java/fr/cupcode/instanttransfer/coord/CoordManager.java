package fr.cupcode.instanttransfer.coord;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Proxy side of the proxy&lt;-&gt;backend coordination channel.
 *
 * <p>Brick 1 (foundation): registers the {@code instanttransfer:coord} channel, sends a ping to the
 * destination backend right after a switch, and logs anything the backends send back. This proves the
 * bidirectional comms path that later bricks will use to orchestrate a coordinated "skip config"
 * switch (announce transfer, exchange arrival state, agree on lockstep).</p>
 */
public final class CoordManager {

    public static final MinecraftChannelIdentifier COORD =
            MinecraftChannelIdentifier.from("instanttransfer:coord");

    private final ProxyServer proxy;
    private final Logger logger;

    public CoordManager(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(server -> {
            String payload = "seamless-ping:" + player.getUsername()
                    + ":" + server.getServerInfo().getName();
            server.sendPluginMessage(COORD, payload.getBytes(StandardCharsets.UTF_8));
            logger.info("[COORD] sent ping to {} for {}",
                    server.getServerInfo().getName(), player.getUsername());
        });
    }

    /**
     * Tell the player's current backend that the arrival freeze is over, so the agent can restore
     * the real flight permission immediately (instead of waiting out its safety-timer grace).
     */
    public void sendUnfreeze(UUID uuid) {
        proxy.getPlayer(uuid).flatMap(Player::getCurrentServer).ifPresent(server -> {
            String payload = "unfreeze:" + uuid;
            server.sendPluginMessage(COORD, payload.getBytes(StandardCharsets.UTF_8));
            logger.info("[COORD] sent unfreeze to {} for {}",
                    server.getServerInfo().getName(), uuid);
        });
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(COORD)) {
            return;
        }
        // Our channel: don't forward it to the client.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof ServerConnection server) {
            String msg = new String(event.getData(), StandardCharsets.UTF_8);
            logger.info("[COORD] recv from {}: {}", server.getServerInfo().getName(), msg);
        }
    }
}
