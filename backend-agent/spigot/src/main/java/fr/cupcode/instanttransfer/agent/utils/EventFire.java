package fr.cupcode.instanttransfer.agent.utils;

import fr.cupcode.instanttransfer.agent.api.events.ArrivalEvent;
import fr.cupcode.instanttransfer.agent.api.events.FlightEvent;
import org.bukkit.Bukkit;

import java.util.UUID;

public class EventFire {

    public static void firePlayerArrive(UUID playerId, String fromServer) {
        ArrivalEvent event = new ArrivalEvent(playerId, fromServer);
        try {
            Bukkit.getPluginManager().callEvent(event);
        }
        catch (Exception e) { Bukkit.getLogger().warning("Error in agent listener: " + e.getMessage()); }
    }

    public static void fireFlight(UUID playerId, int ticks) {
        FlightEvent event = new FlightEvent(playerId, ticks);
        try {
            Bukkit.getPluginManager().callEvent(event);
        }
        catch (Exception e) { Bukkit.getLogger().warning("Error in agent listener: " + e.getMessage()); }
    }
}
