package fr.cupcode.instanttransfer.agent;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backend agent for the coordinated seamless-transfer system.
 *
 * <ul>
 *   <li>packetevents listener logging the config-phase handshake from the server's side.</li>
 *   <li>The {@code instanttransfer:coord} plugin-messaging channel (proxy&lt;-&gt;backend backbone).</li>
 *   <li>Arrival fix: on a seamless switch the proxy sends no Respawn (to avoid the loading screen), so
 *       the client's fall distance is not reset and the player would take fall damage on arrival. We
 *       zero the fall distance on join and cancel fall damage for a short grace window.</li>
 * </ul>
 */
public final class AgentPlugin extends JavaPlugin implements PluginMessageListener, Listener {

    public static final String COORD_CHANNEL = "instanttransfer:coord";

    /** Ticks of fall-damage immunity granted right after a player arrives on this backend. */
    private static final long FALL_GRACE_TICKS = 60L; // ~3 seconds

    private final Set<UUID> fallGrace = ConcurrentHashMap.newKeySet();

    /** Players holding a temporary arrival allowFlight grant, revoked on proxy unfreeze or timeout. */
    private final Set<UUID> flightGrace = ConcurrentHashMap.newKeySet();

    /** Verbose logging ([DIAG-BACKEND] handshake dump, [COORD] messages, [fall-guard] details). */
    private boolean debug = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        debug = getConfig().getBoolean("debug", true);

        if (debug) {
            PacketEvents.getAPI().getEventManager().registerListener(
                    new AgentPacketListener(getLogger()), PacketListenerPriority.NORMAL);
        }

        getServer().getMessenger().registerOutgoingPluginChannel(this, COORD_CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, COORD_CHANNEL, this);

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("InstantTransferAgent enabled (coord channel '" + COORD_CHANNEL
                + "', arrival fall/flight guard, debug=" + debug + ").");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        getServer().getMessenger().unregisterIncomingPluginChannel(this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.setFallDistance(0f);
        UUID uuid = player.getUniqueId();
        fallGrace.add(uuid);
        if (debug) {
            getLogger().info("[fall-guard] arrival grace armed for " + player.getName()
                    + " (" + FALL_GRACE_TICKS + " ticks)");
        }

        // The proxy freezes the arriving player with a client-side flight packet until the arrival
        // chunk is delivered. Allow flight server-side so that hover can never trigger the "Flying
        // is not enabled" kick. Revoked as soon as the proxy signals the unfreeze over the coord
        // channel (usually a few hundred ms); the grace timer is only a fallback.
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR
                && !player.getAllowFlight()) {
            player.setAllowFlight(true);
            flightGrace.add(uuid);
            getServer().getScheduler().runTaskLater(this, () -> revokeFlightGrace(uuid),
                    FALL_GRACE_TICKS);
        }

        // Capture the intended arrival position. On a seamless switch the client is teleported before
        // its arrival chunk is loaded, so with no collision it can sink ~1 block into the ground.
        final Location arrival = player.getLocation().clone();

        // Keep zeroing fall distance, and snap the player back up if they sank while the chunk loaded.
        for (int t = 1; t <= 20; t++) {
            getServer().getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.setFallDistance(0f);
                Location now = player.getLocation();
                double dx = now.getX() - arrival.getX();
                double dz = now.getZ() - arrival.getZ();
                // Only correct if they haven't intentionally walked away and they are below arrival.
                if (now.getWorld() == arrival.getWorld() && dx * dx + dz * dz < 4.0
                        && now.getY() < arrival.getY() - 0.05) {
                    player.teleport(arrival);
                }
            }, t);
        }
        getServer().getScheduler().runTaskLater(this, () -> fallGrace.remove(uuid), FALL_GRACE_TICKS);
    }

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

    /** Take back the temporary arrival flight grant (idempotent, main thread). */
    private void revokeFlightGrace(UUID uuid) {
        if (!flightGrace.remove(uuid)) {
            return;
        }
        Player player = getServer().getPlayer(uuid);
        if (player != null && player.isOnline() && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
            if (debug) {
                getLogger().info("[fall-guard] flight grace revoked for " + player.getName());
            }
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
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
                getServer().getScheduler().runTask(this, () -> revokeFlightGrace(uuid));
            } catch (IllegalArgumentException e) {
                getLogger().warning("[COORD] bad unfreeze payload: " + msg);
            }
        }
    }
}
