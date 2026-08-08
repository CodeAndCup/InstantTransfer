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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Backend agent for coordinated seamless-transfer system.
 * Works on Spigot and Paper (uses Bukkit scheduler on both).
 */
public final class AgentPlugin extends JavaPlugin implements PluginMessageListener {

    public static final String COORD_CHANNEL = "instanttransfer:coord";

    @Override
    public void onEnable() {
        ConfigValue.setDebug(getConfig().getBoolean("debug", true));
        getLogger().info("InstantTransferAgent enabled (coord channel '" + COORD_CHANNEL + "', arrival fall/flight guard, debug=" + ConfigValue.isDebug() + ").");

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

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!COORD_CHANNEL.equals(channel)) return;
        String msg = new String(message, StandardCharsets.UTF_8);
        if (ConfigValue.isDebug()) getLogger().info("[COORD] received via " + player.getName() + ": " + msg);

        if (msg.startsWith("unfreeze:")) {
            try {
                UUID uuid = UUID.fromString(msg.substring("unfreeze:".length()));
                getServer().getScheduler().runTaskLater(this,() -> AgentAPI.revokeFlightGrace(uuid), 0);
            } catch (IllegalArgumentException e) {
                getLogger().warning("[COORD] bad unfreeze payload: " + msg);
            }
        } else if (msg.startsWith("arrival:")) {
            String[] parts = msg.substring("arrival:".length()).split(":", 2);
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