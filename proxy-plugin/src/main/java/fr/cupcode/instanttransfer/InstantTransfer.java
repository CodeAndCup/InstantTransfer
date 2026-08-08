package fr.cupcode.instanttransfer;

import fr.cupcode.instanttransfer.api.InstantTransferAPI;
import fr.cupcode.instanttransfer.api.TransferListener;
import fr.cupcode.instanttransfer.api.TransferStartEvent;
import fr.cupcode.instanttransfer.api.TransferCompleteEvent;
import fr.cupcode.instanttransfer.api.TransferTimeoutEvent;
import fr.cupcode.instanttransfer.command.TransferCommand;
import fr.cupcode.instanttransfer.config.PluginConfig;
import fr.cupcode.instanttransfer.coord.CoordManager;
import fr.cupcode.instanttransfer.packet.EntityIdRemapper;
import fr.cupcode.instanttransfer.packet.SeamlessPacketListener;
import fr.cupcode.instanttransfer.switching.SwitchManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Plugin(
        id = "instanttransfer",
        name = "InstantTransfer",
        version = "0.1.0",
        description = "Seamless (no-loading-screen) backend server switching for Velocity.",
        authors = {"CupCode"},
        dependencies = {@Dependency(id = "packetevents")}
)
public final class InstantTransfer implements InstantTransferAPI {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Messages messages;

    private PluginConfig config;
    private SwitchManager switchManager;
    private CoordManager coordManager;

    /** Players who have requested an instant transfer via /transfer command or API. */
    private final ConcurrentHashMap<UUID, Boolean> instantTransferRequested = new ConcurrentHashMap<>();

    /** Registered transfer listeners. */
    private final List<TransferListener> listeners = new CopyOnWriteArrayList<>();

    @Inject
    public InstantTransfer(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.messages = new Messages(dataDirectory);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        config = PluginConfig.load(dataDirectory, logger);

        if (!config.seamlessEnabled()) {
            logger.info("[InstantTransfer] seamless switching is DISABLED in config; plugin is idle.");
            return;
        }

        // Proxy<->backend coordination channel (arrival unfreeze + diagnostics).
        proxy.getChannelRegistrar().register(CoordManager.COORD);
        coordManager = new CoordManager(proxy, logger);
        proxy.getEventManager().register(this, coordManager);

        switchManager = new SwitchManager(config, logger, this);
        proxy.getEventManager().register(this, switchManager);

        // packetevents is provided by the separately installed packetevents-velocity plugin.
        // The remapper runs at LOW (i.e. earlier) so the tracker below only sees client-space ids.
        PacketEvents.getAPI().getEventManager().registerListener(
                new EntityIdRemapper(switchManager, config, logger),
                PacketListenerPriority.LOW);
        PacketEvents.getAPI().getEventManager().registerListener(
                new SeamlessPacketListener(switchManager, coordManager, config, logger, this),
                PacketListenerPriority.NORMAL);

        // Register /transfer command
        CommandManager commandManager = proxy.getCommandManager();
        commandManager.register(
                commandManager.metaBuilder("transfer")
                        .aliases("instanttransfer", "it")
                        .plugin(this)
                        .build(),
                new TransferCommand(proxy, this)
        );

        logger.info("[InstantTransfer] enabled (require-same-dimension={}, debug={}).",
                config.requireSameDimension(), config.debug());
    }

    // === API Implementation ===

    @Override
    public CompletableFuture<InstantTransferAPI.TransferResult> requestTransfer(Player player, RegisteredServer target) {
        if (!config.seamlessEnabled()) {
            return CompletableFuture.completedFuture(InstantTransferAPI.TransferResult.DISABLED);
        }

        UUID uuid = player.getUniqueId();

        // Check if already on target server
        if (player.getCurrentServer().map(s -> s.getServerInfo().equals(target.getServerInfo())).orElse(false)) {
            return CompletableFuture.completedFuture(InstantTransferAPI.TransferResult.ALREADY_ON_SERVER);
        }

        // Check if already transferring
        if (instantTransferRequested.containsKey(uuid)) {
            return CompletableFuture.completedFuture(InstantTransferAPI.TransferResult.ALREADY_TRANSFERRING);
        }

        // Mark as instant transfer requested
        instantTransferRequested.put(uuid, true);

        // Safety timeout: clear flag if transfer doesn't start within 10 seconds
        proxy.getScheduler().buildTask(this, () -> {
            instantTransferRequested.remove(uuid);
            if (config.debug()) {
                logger.debug("[InstantTransfer] Cleared stale instant transfer flag for {}", player.getUsername());
            }
        }).delay(java.time.Duration.ofSeconds(10)).schedule();

        // Trigger the transfer by connecting the player to the target server
        player.createConnectionRequest(target).connect();

        // The result will be determined by the SwitchManager.onPreConnect event
        // We return a future that completes when the transfer finishes
        // For now, return SUCCESS immediately since the actual transfer is async
        return CompletableFuture.completedFuture(InstantTransferAPI.TransferResult.SUCCESS);
    }

    @Override
    public boolean isTransferring(UUID playerId) {
        return instantTransferRequested.containsKey(playerId);
    }

    @Override
    public CompletableFuture<InstantTransferAPI.TransferResult> requestTransfer(Player player, String targetServerName) {
        return proxy.getServer(targetServerName)
                .map(server -> requestTransfer(player, server))
                .orElseGet(() -> CompletableFuture.completedFuture(InstantTransferAPI.TransferResult.SERVER_NOT_FOUND));
    }

    @Override
    public void registerListener(TransferListener listener) {
        listeners.add(listener);
    }

    @Override
    public void unregisterListener(TransferListener listener) {
        listeners.remove(listener);
    }

    // === Internal API ===

    /**
     * Called by SwitchManager when a transfer is about to start.
     * Returns true if this is an instant transfer (requested via /transfer or API).
     */
    public boolean consumeInstantTransferRequest(UUID playerId) {
        return instantTransferRequested.remove(playerId) != null;
    }

    /**
     * Called when an instant transfer starts.
     */
    public void fireTransferStart(Player player, RegisteredServer from, RegisteredServer to) {
        TransferStartEvent event = new TransferStartEvent(player, from, to);
        for (TransferListener listener : listeners) {
            try {
                listener.onTransferStart(event);
            } catch (Exception e) {
                logger.warn("Error in transfer listener", e);
            }
        }
    }

    /**
     * Called when an instant transfer completes.
     */
    public void fireTransferComplete(Player player, RegisteredServer from, RegisteredServer to, boolean success, String reason) {
        TransferCompleteEvent event = new TransferCompleteEvent(player, from, to, success, reason);
        for (TransferListener listener : listeners) {
            try {
                listener.onTransferComplete(event);
            } catch (Exception e) {
                logger.warn("Error in transfer listener", e);
            }
        }
    }

    /**
     * Called when an instant transfer times out.
     */
    public void fireTransferTimeout(Player player, RegisteredServer from, RegisteredServer to) {
        TransferTimeoutEvent event = new TransferTimeoutEvent(player, from, to);
        for (TransferListener listener : listeners) {
            try {
                listener.onTransferTimeout(event);
            } catch (Exception e) {
                logger.warn("Error in transfer listener", e);
            }
        }
    }

    public Messages getMessages() {
        return messages;
    }

    public String getMessage(String key, Object... args) {
        return messages.get(key, args);
    }

    public PluginConfig getConfig() {
        return config;
    }

    public SwitchManager getSwitchManager() {
        return switchManager;
    }

    public CoordManager getCoordManager() {
        return coordManager;
    }

    public ProxyServer getProxy() {
        return proxy;
    }

    public static InstantTransfer getAPI() {
        // This will be set by the plugin instance
        return InstantTransferAPIProvider.INSTANCE;
    }

    private static class InstantTransferAPIProvider {
        static InstantTransfer INSTANCE;
    }

    // Set the static instance for static API access
    {
        InstantTransferAPIProvider.INSTANCE = this;
    }
}