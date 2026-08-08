package fr.cupcode.instanttransfer.agent;

import fr.cupcode.instanttransfer.agent.api.InstantTransferAgentAPI;
import fr.cupcode.instanttransfer.agent.utils.ConfigValue;
import fr.cupcode.instanttransfer.agent.utils.EventFire;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AgentAPI implements InstantTransferAgentAPI {

    private static final Set<UUID> fallGrace = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> flightGrace = ConcurrentHashMap.newKeySet();
    
    @Override
    public void onPlayerArrive(UUID playerId, String fromServer) {
        EventFire.firePlayerArrive(playerId, fromServer);
    }

    @Override
    public void grantArrivalFlight(UUID playerId, int ticks) {
        Player player = Bukkit.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) return;
        if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR && !player.getAllowFlight()) {
            player.setAllowFlight(true);
            getFlightGrace().add(playerId);
            if (ConfigValue.isDebug()) {
                Bukkit.getLogger().info("[flight-guard] granted arrival flight for " + player.getName() + " (" + ticks + " ticks)");
            }
        }
        EventFire.fireFlight(playerId, ticks); //Grant flight
    }

    @Override
    public void revokeArrivalFlight(UUID playerId) {
        revokeFlightGrace(playerId);
    }

    @Override
    public boolean hasArrivalFlight(UUID playerId) {
        return getFlightGrace().contains(playerId);
    }

    @Override
    public void addFallGrace(UUID uuid) { getFallGrace().add(uuid); }
    @Override
    public void removeFallGrace(UUID uuid) { getFallGrace().remove(uuid); }
    @Override
    public boolean hasFallGrace(UUID uuid) { return getFallGrace().contains(uuid); }
    @Override
    public boolean isDebug() { return ConfigValue.isDebug(); }

    public static boolean consumeFallGrace(UUID uuid) { return getFallGrace().remove(uuid); }

    public static boolean hasFlightGrace(UUID uuid) { return getFlightGrace().contains(uuid); }

    public static void revokeFlightGrace(UUID uuid) {
        if (!getFlightGrace().remove(uuid)) return;

        Player player = Bukkit.getServer().getPlayer(uuid);
        if (player != null && player.isOnline() && player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
            if (ConfigValue.isDebug()){
                Bukkit.getLogger().info("[fall-guard] flight grace revoked for " + player.getName());
            }
        }
        EventFire.fireFlight(uuid, (int) ConfigValue.getFallGraceTicks()); //Revoke flight
    }

    protected static Set<UUID> getFallGrace() {
        return fallGrace;
    }
    protected static Set<UUID> getFlightGrace() {
        return flightGrace;
    }
}
