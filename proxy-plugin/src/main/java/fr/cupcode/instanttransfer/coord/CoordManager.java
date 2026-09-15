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
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CoordManager {

    public static final MinecraftChannelIdentifier COORD =
            MinecraftChannelIdentifier.from("instanttransfer:coord");

    private final ProxyServer proxy;
    private final Logger logger;
    private final String coordHmacSecret;

    public CoordManager(ProxyServer proxy, Logger logger, String coordHmacSecret) {
        this.proxy = proxy;
        this.logger = logger;
        this.coordHmacSecret = coordHmacSecret;
    }

    private String signHmac(String payload, String secret) {
        if (secret == null || secret.isEmpty()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            logger.warn("[COORD] HMAC signing failed: {}", e.toString());
            return null;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private boolean verifyHmac(String payload, String receivedHmac, String secret) {
        if (secret == null || secret.isEmpty()) {
            return true; // no secret configured → allow for backward compatibility
        }
        int lastColon = payload.lastIndexOf(':');
        if (lastColon < 1) {
            return false;
        }
        String signingPayload = payload.substring(0, lastColon);
        String computedHmac = signHmac(signingPayload, secret);
        if (computedHmac == null) {
            return false;
        }
        return computedHmac.equals(receivedHmac);
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
            String payload = "unfreeze:" + uuid + ":" + signHmac("unfreeze:" + uuid, coordHmacSecret);
            server.sendPluginMessage(COORD, payload.getBytes(StandardCharsets.UTF_8));
            logger.info("[COORD] sent unfreeze to {} for {}",
                    server.getServerInfo().getName(), uuid);
        });
    }

    /**
     * Notify the backend that a player has arrived via instant transfer.
     * This triggers the backend agent to grant arrival flight and fire arrival events.
     *
     * @param uuid the player's UUID
     * @param fromServer the server the player came from
     */
    public void sendArrival(UUID uuid, String fromServer) {
        proxy.getPlayer(uuid).flatMap(Player::getCurrentServer).ifPresent(server -> {
            String payload = "arrival:" + uuid + ":" + fromServer + ":" + signHmac("arrival:" + uuid + ":" + fromServer, coordHmacSecret);
            server.sendPluginMessage(COORD, payload.getBytes(StandardCharsets.UTF_8));
            logger.info("[COORD] sent arrival to {} for {} (from {})",
                    server.getServerInfo().getName(), uuid, fromServer);
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
            // Verify HMAC if a signature is appended (last segment after final colon)
            if (!verifyHmac(msg, null, coordHmacSecret)) {
                logger.warn("[COORD] invalid HMAC on coord message from {}: {}", server.getServerInfo().getName(), msg);
                return;
            }
            // Extract inner message without HMAC suffix for legacy compatibility
            // The HMAC signature is always the last colon-delimited segment
            int lastColon = msg.lastIndexOf(':');
            String innerMsg = (lastColon >= 0) ? msg.substring(0, lastColon) : msg;
            logger.info("[COORD] recv from {}: {}", server.getServerInfo().getName(), innerMsg);
        }
    }
}