package fr.cupcode.instanttransfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Simple message system for the plugin.
 */
public final class Messages {

    private final Map<String, String> messages = new HashMap<>();

    public Messages(Path dataDirectory) {
        loadDefaults();
        loadCustom(dataDirectory);
    }

    private void loadDefaults() {
        messages.put("command.players-only", "&cThis command can only be run by players.");
        messages.put("command.usage", "&cUsage: /transfer <server>");
        messages.put("command.server-not-found", "&cServer '&e{0}&c' not found.");
        messages.put("command.already-on-server", "&cYou are already on server '&e{0}&c'.");
        messages.put("command.no-permission", "&cYou don't have permission to use this command.");
        messages.put("command.transfer-started", "&aInitiating instant transfer to '&e{0}&a'...");
        messages.put("command.already-transferring", "&cYou are already being transferred.");
        messages.put("command.disabled", "&cInstant transfer is disabled in config.");
        messages.put("command.error", "&cAn error occurred while starting the transfer.");
        messages.put("command.unknown-error", "&cAn unknown error occurred.");
    }

    private void loadCustom(Path dataDirectory) {
        Path file = dataDirectory.resolve("messages.properties");
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                Properties props = new Properties();
                props.load(in);
                for (String key : props.stringPropertyNames()) {
                    messages.put(key, props.getProperty(key));
                }
            } catch (IOException e) {
                // Ignore custom messages load errors
            }
        }
    }

    public String get(String key, Object... args) {
        String msg = messages.get(key);
        if (msg == null) {
            return "[Missing message: " + key + "]";
        }
        for (int i = 0; i < args.length; i++) {
            msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return msg.replace("&", "§");
    }
}