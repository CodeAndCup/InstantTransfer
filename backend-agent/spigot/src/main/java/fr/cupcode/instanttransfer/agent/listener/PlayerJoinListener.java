package fr.cupcode.instanttransfer.agent.listener;

import fr.cupcode.instanttransfer.agent.AgentAPI;
import fr.cupcode.instanttransfer.agent.AgentPlugin;
import fr.cupcode.instanttransfer.agent.utils.ConfigValue;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public class PlayerJoinListener implements Listener {
    private final AgentPlugin plugin;

    public PlayerJoinListener(AgentPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (AgentAPI.consumeFallGrace(uuid)) {
            if (ConfigValue.isDebug()) plugin.getLogger().info("[fall-guard] " + e.getPlayer().getName() + " joined with fall grace, consuming");
        }
    }
}