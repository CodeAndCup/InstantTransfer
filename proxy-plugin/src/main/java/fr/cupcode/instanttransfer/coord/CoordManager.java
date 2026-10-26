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
import java.security.MessageDigest;
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

    private static String getHmacPayload(String msg) {
        int lastColon = msg.lastIndexOf(':');
        if (lastColon < 1) return msg;
        return msg.substring(0, lastColon);
    }

    private static String getReceivedHmac(String msg) {
        int lastColon = msg.lastIndexOf(':');
        if (lastColon < 1) return null;
        return msg.substring(lastColon + 1);
    }

    private boolean verifyHmac(String msg, String secret) {
        if (secret == null || secret.isEmpty()) {
            return true;
        }
        String receivedHmac = getReceivedHmac(msg);
        if (receivedHmac == null || receivedHmac.isEmpty()) {
            return false;
        }
        String signingPayload = getHmacPayload(msg);
        String computedHmac = signHmac(signingPayload, secret);
        if (computedHmac == null) {
            return false;
        }
        return MessageDigest.isEqual(
                computedHmac.getBytes(StandardCharsets.UTF_8),
                receivedHmac.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isSecured(String secret) {
        return secret != null && !secret.isEmpty();
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        player.getCurrentServer().ifPresent(server -> {
            String base = "seamless-ping:" + player.getUsername()
                    + ":" + server.getServerInfo().getName();
            String hmac = signHmac(base, coordHmacSecret);
            String payload = (hmac == null) ? base : base + ":" + hmac;
            server.sendPluginMessage(COORD, payload.getBytes(StandardCharsets.UTF_8));
            logger.info("[COORD] sent ping to {} for {}",
                    server.getServerInfo().getName(), player.getUsername());
        });
    }

    public void sendUnfreeze(UUID uuid) {
        proxy.getPlayer(uuid).flatMap(Player::getCurrentServer).ifPresent(server -> {
            String base = "unfreeze:" + uuid;
            String hmac = signHmac(base, coordHmacSecret);
            String payload = (hmac == null) ? base : base + ":" + hmac;
            server.sendPluginMessage(COORD, payload.getBytes(StandardCharsets.UTF_8));
            logger.info("[COORD] sent unfreeze to {} for {}",
                    server.getServerInfo().getName(), uuid);
        });
    }

    public void sendArrival(UUID uuid, String fromServer) {
        proxy.getPlayer(uuid).flatMap(Player::getCurrentServer).ifPresent(server -> {
            String base = "arrival:" + uuid + ":" + fromServer;
            String hmac = signHmac(base, coordHmacSecret);
            String payload = (hmac == null) ? base : base + ":" + hmac;
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
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof ServerConnection server) {
            String msg = new String(event.getData(), StandardCharsets.UTF_8);
            if (!verifyHmac(msg, coordHmacSecret)) {
                logger.warn("[COORD] invalid HMAC on coord message from {}: {}", server.getServerInfo().getName(), msg);
                return;
            }
            // Only strip the HMAC suffix when secured; in legacy mode keep msg as-is.
            String innerMsg = msg;
            if (isSecured(coordHmacSecret)) {
                int lastColon = msg.lastIndexOf(':');
                innerMsg = (lastColon >= 0) ? msg.substring(0, lastColon) : msg;
            }
            logger.info("[COORD] recv from {}: {}", server.getServerInfo().getName(), innerMsg);
        }
    }
}
