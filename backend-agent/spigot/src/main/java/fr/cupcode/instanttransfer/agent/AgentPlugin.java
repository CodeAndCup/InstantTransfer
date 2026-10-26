package fr.cupcode.instanttransfer.agent;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import fr.cupcode.instanttransfer.agent.api.InstantTransferAgentAPI;
import fr.cupcode.instanttransfer.agent.api.events.ArrivalEvent;
import fr.cupcode.instanttransfer.agent.api.events.FlightEvent;
import fr.cupcode.instanttransfer.agent.listener.EntityDamageListener;
import fr.cupcode.instanttransfer.agent.listener.PlayerJoinListener;
import fr.cupcode.instanttransfer.agent.packet.AgentPacketListener;
import fr.cupcode.instanttransfer.agent.utils.ConfigValue;
import fr.cupcode.instanttransfer.agent.utils.EventFire;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backend agent for coordinated seamless-transfer system.
 * Works on Spigot and Paper (uses Bukkit scheduler on both).
 */
public final class AgentPlugin extends JavaPlugin implements PluginMessageListener {

    public static final String COORD_CHANNEL = "instanttransfer:coord";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigValue.setDebug(getConfig().getBoolean("debug", false));
        String secret = getConfig().getString("coord-hmac-secret", "");
        ConfigValue.setCoordHmacSecret(secret != null ? secret.trim() : "");
        if (ConfigValue.getCoordHmacSecret().isEmpty()) {
            getLogger().warning("[COORD] coord-hmac-secret is UNSET: coord channel accepts unauthenticated messages (legacy mode). Set the same secret on proxy and all backends.");
        }
        getLogger().info("InstantTransferAgent enabled (coord channel '" + COORD_CHANNEL + "', arrival fall/flight guard, debug=" + ConfigValue.isDebug() + ", coord-hmac-secret=" + (ConfigValue.getCoordHmacSecret().isEmpty() ? "unset" : "set") + ").");

        getServer().getMessenger().registerOutgoingPluginChannel(this, COORD_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, COORD_CHANNEL, this);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new EntityDamageListener(this), this);

        if (ConfigValue.isDebug()) {
            PacketEvents.getAPI().getEventManager().registerListener(new AgentPacketListener(this), PacketListenerPriority.NORMAL);
        }
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    private static String getHmacPayload(String msg) {
        // The HMAC signature is the last colon-delimited segment.
        // The signing payload is everything before the last colon.
        int lastColon = msg.lastIndexOf(':');
        if (lastColon < 1) return msg;
        return msg.substring(0, lastColon);
    }

    private static String getReceivedHmac(String msg) {
        int lastColon = msg.lastIndexOf(':');
        if (lastColon < 1) return null;
        return msg.substring(lastColon + 1);
    }

    private static String extractInnerMsg(String msg) {
        // The HMAC signature is always the last colon-delimited segment if present.
        int lastColon = msg.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < msg.length() - 1) {
            return msg.substring(0, lastColon);
        }
        return msg;
    }

    private boolean verifyCoordHmac(String msg, String secret) {
        if (secret == null || secret.isEmpty()) {
            return true; // no secret configured -> allow for backward compatibility
        }
        String payload = getHmacPayload(msg);
        String receivedHmac = getReceivedHmac(msg);
        if (receivedHmac == null || receivedHmac.isEmpty()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] computed = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedHex = bytesToHex(computed);
            return MessageDigest.isEqual(
                    computedHex.getBytes(StandardCharsets.UTF_8),
                    receivedHmac.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            getLogger().warning("[COORD] HMAC verification error: " + e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!COORD_CHANNEL.equals(channel)) return;
        String msg = new String(message, StandardCharsets.UTF_8);
        if (ConfigValue.isDebug()) getLogger().info("[COORD] received via " + player.getName() + ": " + msg);

        // Verify HMAC if a coordination secret is configured;
        // messages without valid HMAC are ignored to prevent spoofing.
        String secret = ConfigValue.getCoordHmacSecret();
        boolean secured = secret != null && !secret.isEmpty();
        if (!verifyCoordHmac(msg, secret)) {
            getLogger().warning("[COORD] invalid HMAC on coord message from " + player.getName() + ": " + msg);
            return;
        }

        // Only strip the HMAC suffix when a secret is configured; otherwise
        // keep the legacy payload untouched (unfreeze:uuid / arrival:uuid:server).
        String innerMsg = secured ? extractInnerMsg(msg) : msg;

        if (innerMsg.startsWith("unfreeze:")) {
            try {
                UUID uuid = UUID.fromString(innerMsg.substring("unfreeze:".length()));
                getServer().getScheduler().runTaskLater(this, () -> AgentAPI.revokeFlightGrace(uuid), 0);
            } catch (IllegalArgumentException e) {
                getLogger().warning("[COORD] bad unfreeze payload: " + msg);
            }
        } else if (innerMsg.startsWith("arrival:")) {
            String[] parts = innerMsg.substring("arrival:".length()).split(":", 2);
            if (parts.length == 2) {
                try {
                    UUID uuid = UUID.fromString(parts[0]);
                    String fromServer = parts[1];
                    new AgentAPI().grantArrivalFlight(uuid, (int) ConfigValue.getFallGraceTicks());
                    EventFire.firePlayerArrive(uuid, fromServer);
                } catch (IllegalArgumentException e) {
                    getLogger().warning("[COORD] bad arrival payload: " + msg + " - " + e.getMessage());
                }
            }
        }
    }
}
