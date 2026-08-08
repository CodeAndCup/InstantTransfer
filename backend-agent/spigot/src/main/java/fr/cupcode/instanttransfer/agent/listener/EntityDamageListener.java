package fr.cupcode.instanttransfer.agent.listener;

import fr.cupcode.instanttransfer.agent.AgentAPI;
import fr.cupcode.instanttransfer.agent.AgentPlugin;
import fr.cupcode.instanttransfer.agent.utils.ConfigValue;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.UUID;

public class EntityDamageListener implements Listener {
    private final AgentPlugin plugin;

    public EntityDamageListener(AgentPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();

        if (AgentAPI.consumeFallGrace(uuid)) {
            if (ConfigValue.isDebug()) plugin.getLogger().info("[fall-guard] " + player.getName() + " took damage, fall grace consumed");
        }

        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && AgentAPI.hasFlightGrace(uuid)) {
            e.setCancelled(true);
            if (ConfigValue.isDebug()) plugin.getLogger().info("[fall-guard] " + player.getName() + " fall damage cancelled (flight grace active)");
        }
    }
}