package fr.cupcode.instanttransfer.config;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Minimal file-backed configuration (no extra dependencies).
 * Stored as {@code <datadir>/config.properties}; created with defaults on first run.
 */
public final class PluginConfig {

    /** Master switch. When false the plugin does nothing and Velocity behaves normally. */
    private boolean seamlessEnabled = true;

    /**
     * When true, a seamless (no-reload) transfer is only attempted if the destination
     * server reports the SAME world/dimension as the current one. If the dimension differs
     * we fall back to Velocity's normal switch (which shows the loading screen).
     * This is the safe default: cross-dimension seamless needs identical registries.
     */
    private boolean requireSameDimension = true;

    /** Verbose per-packet logging. Handy for the first tests; turn off in production. */
    private boolean debug = true;

    private PluginConfig() {
    }

    public boolean seamlessEnabled() {
        return seamlessEnabled;
    }

    public boolean requireSameDimension() {
        return requireSameDimension;
    }

    public boolean debug() {
        return debug;
    }

    public static PluginConfig load(Path dataDirectory, Logger logger) {
        PluginConfig config = new PluginConfig();
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            Properties props = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
                config.seamlessEnabled = parseBool(props, "seamless-enabled", config.seamlessEnabled);
                config.requireSameDimension = parseBool(props, "require-same-dimension", config.requireSameDimension);
                config.debug = parseBool(props, "debug", config.debug);
            } else {
                props.setProperty("seamless-enabled", Boolean.toString(config.seamlessEnabled));
                props.setProperty("require-same-dimension", Boolean.toString(config.requireSameDimension));
                props.setProperty("debug", Boolean.toString(config.debug));
                try (OutputStream out = Files.newOutputStream(file)) {
                    props.store(out, "InstantTransfer configuration");
                }
                logger.info("[InstantTransfer] Wrote default config to {}", file);
            }
        } catch (IOException e) {
            logger.warn("[InstantTransfer] Could not read/write config, using defaults", e);
        }
        return config;
    }

    private static boolean parseBool(Properties props, String key, boolean def) {
        String raw = props.getProperty(key);
        return raw == null ? def : Boolean.parseBoolean(raw.trim());
    }
}
