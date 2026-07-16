package fr.cupcode.instanttransfer;

import fr.cupcode.instanttransfer.config.PluginConfig;
import fr.cupcode.instanttransfer.coord.CoordManager;
import fr.cupcode.instanttransfer.packet.EntityIdRemapper;
import fr.cupcode.instanttransfer.packet.SeamlessPacketListener;
import fr.cupcode.instanttransfer.switching.SwitchManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;

@Plugin(
        id = "instanttransfer",
        name = "InstantTransfer",
        version = "0.1.0",
        description = "Seamless (no-loading-screen) backend server switching for Velocity.",
        authors = {"CupCode"},
        dependencies = {@Dependency(id = "packetevents")}
)
public final class InstantTransfer {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public InstantTransfer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        PluginConfig config = PluginConfig.load(dataDirectory, logger);

        if (!config.seamlessEnabled()) {
            logger.info("[InstantTransfer] seamless switching is DISABLED in config; plugin is idle.");
            return;
        }

        // Proxy<->backend coordination channel (arrival unfreeze + diagnostics).
        proxy.getChannelRegistrar().register(CoordManager.COORD);
        CoordManager coordManager = new CoordManager(proxy, logger);
        proxy.getEventManager().register(this, coordManager);

        SwitchManager switchManager = new SwitchManager(config, logger);
        proxy.getEventManager().register(this, switchManager);

        // packetevents is provided by the separately installed packetevents-velocity plugin.
        // The remapper runs at LOW (i.e. earlier) so the tracker below only sees client-space ids.
        PacketEvents.getAPI().getEventManager().registerListener(
                new EntityIdRemapper(switchManager, config, logger),
                PacketListenerPriority.LOW);
        PacketEvents.getAPI().getEventManager().registerListener(
                new SeamlessPacketListener(switchManager, coordManager, config, logger),
                PacketListenerPriority.NORMAL);

        logger.info("[InstantTransfer] enabled (require-same-dimension={}, debug={}).",
                config.requireSameDimension(), config.debug());
    }
}
