package fr.cupcode.instanttransfer.command;

import fr.cupcode.instanttransfer.InstantTransfer;
import fr.cupcode.instanttransfer.api.InstantTransferAPI;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Command for instant transfer: /transfer <server>
 */
public final class TransferCommand implements SimpleCommand {

    private final ProxyServer proxy;
    private final InstantTransfer plugin;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacySection();

    public TransferCommand(ProxyServer proxy, InstantTransfer plugin) {
        this.proxy = proxy;
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (!(source instanceof Player player)) {
            source.sendMessage(serializer.deserialize(plugin.getMessage("command.players-only")));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(serializer.deserialize(plugin.getMessage("command.usage")));
            return;
        }

        String targetName = args[0];
        Optional<RegisteredServer> targetOpt = proxy.getServer(targetName);

        if (targetOpt.isEmpty()) {
            player.sendMessage(serializer.deserialize(plugin.getMessage("command.server-not-found", targetName)));
            return;
        }

        RegisteredServer target = targetOpt.get();

        // Check if already on target server
        if (player.getCurrentServer().map(s -> s.getServerInfo().equals(target.getServerInfo())).orElse(false)) {
            player.sendMessage(serializer.deserialize(plugin.getMessage("command.already-on-server", targetName)));
            return;
        }

        // Check permission
        if (!player.hasPermission("instanttransfer.command.transfer")) {
            player.sendMessage(serializer.deserialize(plugin.getMessage("command.no-permission")));
            return;
        }

        // Request instant transfer via API
        InstantTransferAPI api = plugin.getAPI();
        api.requestTransfer(player, target).thenAccept(result -> {
            switch (result) {
                case SUCCESS -> player.sendMessage(serializer.deserialize(plugin.getMessage("command.transfer-started", targetName)));
                case ALREADY_ON_SERVER -> player.sendMessage(serializer.deserialize(plugin.getMessage("command.already-on-server", targetName)));
                case ALREADY_TRANSFERRING -> player.sendMessage(serializer.deserialize(plugin.getMessage("command.already-transferring")));
                case SERVER_NOT_FOUND -> player.sendMessage(serializer.deserialize(plugin.getMessage("command.server-not-found", targetName)));
                case DISABLED -> player.sendMessage(serializer.deserialize(plugin.getMessage("command.disabled")));
                case ERROR -> player.sendMessage(serializer.deserialize(plugin.getMessage("command.error")));
                default -> player.sendMessage(serializer.deserialize(plugin.getMessage("command.unknown-error")));
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 1) {
            String input = args[0].toLowerCase();
            return proxy.getAllServers().stream()
                    .map(RegisteredServer::getServerInfo)
                    .map(info -> info.getName())
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("instanttransfer.command.transfer");
    }
}