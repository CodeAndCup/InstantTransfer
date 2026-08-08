package fr.cupcode.instanttransfer.agent.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import fr.cupcode.instanttransfer.agent.AgentAPI;
import fr.cupcode.instanttransfer.agent.AgentPlugin;
import fr.cupcode.instanttransfer.agent.utils.ConfigValue;
import org.bukkit.entity.Player;

public class AgentPacketListener implements PacketListener {
    private final AgentPlugin plugin;

    public AgentPacketListener(AgentPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING) {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
            if (flying.hasPositionChanged()) {
                Player player = event.getPlayer();
                if (player != null && AgentAPI.hasFlightGrace(player.getUniqueId())) {
                    if (ConfigValue.isDebug()) plugin.getLogger().info("[DIAG-BACKEND] flight grace player moved: " + player.getName()
                            + " pos=" + flying.getLocation().getX() + "," + flying.getLocation().getY() + "," + flying.getLocation().getZ());
                }
            }
        }
    }
}