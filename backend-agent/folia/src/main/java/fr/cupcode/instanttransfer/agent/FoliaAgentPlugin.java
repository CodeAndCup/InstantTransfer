package fr.cupcode.instanttransfer.agent;

import io.papermc.folia.entity.Player;
import io.papermc.folia.scheduler.FoliaScheduler;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Folia-specific backend agent using region-aware scheduling.
 * <p>
 * On Folia, tasks are scheduled on the player's region scheduler to avoid
 * cross-thread issues when the player moves between regions.
 */
public final class FoliaAgentPlugin extends AgentPlugin {

    @Override
    public void onEnable() {
        super.onEnable();
        getLogger().info("Folia region-aware scheduling enabled.");
    }

    @Override
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Use Folia's region scheduler for all per-player tasks
        Player player = (Player) event.getPlayer();
        FoliaScheduler scheduler = player.getScheduler();

        // Fall distance zeroing
        player.setFallDistance(0f);
        UUID uuid = player.getUniqueId();
        fallGrace.add(uuid);
        if (debug) {
            getLogger().info("[fall-guard] arrival grace armed for " + player.getName()
                    + " (" + FALL_GRACE_TICKS + " ticks)");
        }

        // Allow flight server-side for non-creative/spectator players
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                && !player.getAllowFlight()) {
            player.setAllowFlight(true);
            flightGrace.add(uuid);
            scheduler.runTaskLater(this, () -> revokeFlightGrace(uuid), FALL_GRACE_TICKS);
        }

        // Capture intended arrival position
        final Location arrival = player.getLocation().clone();

        // Keep zeroing fall distance and snap back if sunk
        for (int t = 1; t <= 20; t++) {
            final int tick = t;
            scheduler.runTaskLater(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.setFallDistance(0f);
                Location now = player.getLocation();
                double dx = now.getX() - arrival.getX();
                double dz = now.getZ() - arrival.getZ();
                if (now.getWorld() == arrival.getWorld() && dx * dx + dz * dz < 4.0
                        && now.getY() < arrival.getY() - 0.05) {
                    player.teleport(arrival);
                }
            }, tick);
        }
        scheduler.runTaskLater(this, () -> fallGrace.remove(uuid), FALL_GRACE_TICKS);
    }

    @Override
    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean arrivalCause = cause == EntityDamageEvent.DamageCause.FALL
                || cause == EntityDamageEvent.DamageCause.FLY_INTO_WALL
                || cause == EntityDamageEvent.DamageCause.SUFFOCATION;
        if (!arrivalCause) {
            return;
        }
        if (event.getEntity() instanceof Player player && fallGrace.contains(player.getUniqueId())) {
            event.setCancelled(true);
            player.setFallDistance(0f);
            if (debug) {
                getLogger().info("[fall-guard] cancelled " + cause + " damage for " + player.getName());
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, org.bukkit.entity.Player player, byte[] message) {
        if (!COORD_CHANNEL.equals(channel)) {
            return;
        }
        String msg = new String(message, StandardCharsets.UTF_8);
        if (debug) {
            getLogger().info("[COORD] received via " + player.getName() + ": " + msg);
        }
        if (msg.startsWith("unfreeze:")) {
            try {
                UUID uuid = UUID.fromString(msg.substring("unfreeze:".length()));
                // Use region scheduler for the unfreeze task
                if (player instanceof Player foliaPlayer) {
                    foliaPlayer.getScheduler().runTask(this, () -> revokeFlightGrace(uuid));
                } else {
                    // Fallback to global scheduler
                    getServer().getScheduler().runTask(this, () -> revokeFlightGrace(uuid));
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("[COORD] bad unfreeze payload: " + msg);
            }
        }
    }
}