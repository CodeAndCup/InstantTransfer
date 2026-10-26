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

    /** Timeout (ms) for the arrival guard. If the arrival chunk doesn't arrive within this time,
     *  a position correction is sent (if absolute coords known) instead of brutally unfreezing. */
    private int guardTimeoutMs = 5000;

    /**
     * Shared secret HMAC used to sign {@code instanttransfer:ids} plugin messages.
     * When set, the EntityIdRemapper will verify the HMAC-SHA256 of the payload portion
     * (clientId:backendId[:gamemode]) against this hex-encoded secret.
     * If the HMAC does not match, the IDs message is ignored and logged as suspicious.
     * NOTE: the Velocity patch (velocity-patch/seamless.patch) currently sends ids
     * unsigned, so this must stay empty until the patch signs its payload.
     */
    private String idsHmacSecret = "";

    /**
     * Shared secret HMAC used to sign {@code instanttransfer:coord} plugin messages.
     * When set, both CoordManager and AgentPlugin will verify the HMAC-SHA256 of the payload
     * against this hex-encoded secret. Messages without a valid HMAC are ignored.
     * Set the same secret on the proxy and the backend agent to authenticate coord messages.
     */
    private String coordHmacSecret = "";

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

    public int guardTimeoutMs() {
        return guardTimeoutMs;
    }

    public String idsHmacSecret() {
        return idsHmacSecret;
    }

    public String coordHmacSecret() {
        return coordHmacSecret;
    }

    public static PluginConfig load(Path dataDirectory, Logger logger) {
        PluginConfig config = new PluginConfig();
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            Properties props = new Properties();
            if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                try (InputStream in = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    props.load(in);
                }
                config.seamlessEnabled = parseBool(props, "seamless-enabled", config.seamlessEnabled);
                config.requireSameDimension = parseBool(props, "require-same-dimension", config.requireSameDimension);
                config.debug = parseBool(props, "debug", config.debug);
                config.guardTimeoutMs = parseInt(props, "guard-timeout-ms", config.guardTimeoutMs);
                if (config.guardTimeoutMs < 1000 || config.guardTimeoutMs > 60000) {
                    logger.warn("[InstantTransfer] guard-timeout-ms value {} is outside recommended range [1000, 60000]; clamping to {}",
                            config.guardTimeoutMs, Math.max(1000, Math.min(60000, config.guardTimeoutMs)));
                    config.guardTimeoutMs = Math.max(1000, Math.min(60000, config.guardTimeoutMs));
                }
                config.idsHmacSecret = parseString(props, "ids-hmac-secret", config.idsHmacSecret);
                config.coordHmacSecret = parseString(props, "coord-hmac-secret", config.coordHmacSecret);
            } else {
                props.setProperty("seamless-enabled", Boolean.toString(config.seamlessEnabled));
                props.setProperty("require-same-dimension", Boolean.toString(config.requireSameDimension));
                props.setProperty("debug", Boolean.toString(config.debug));
                props.setProperty("guard-timeout-ms", Integer.toString(config.guardTimeoutMs));
                props.setProperty("ids-hmac-secret", config.idsHmacSecret);
                props.setProperty("coord-hmac-secret", config.coordHmacSecret);
                try (OutputStream out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                    props.store(out, "InstantTransfer configuration");
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    logger.warn("[InstantTransfer] Config file exists as non-regular file, using defaults", e);
                }
                logger.info("[InstantTransfer] Wrote default config to {}", file);
            }
        } catch (IOException e) {
            logger.warn("[InstantTransfer] Could not read/write config, using defaults", e);
        }
        if (config.coordHmacSecret == null || config.coordHmacSecret.isEmpty()) {
            logger.warn("[InstantTransfer] coord-hmac-secret is UNSET: coord channel accepts unauthenticated messages (legacy mode). Set the same secret on proxy and all backends.");
        }
        if (config.idsHmacSecret == null || config.idsHmacSecret.isEmpty()) {
            logger.warn("[InstantTransfer] ids-hmac-secret is UNSET: ids channel accepts unauthenticated messages (legacy mode). Keep empty until the Velocity patch signs its payload.");
        }
        return config;
    }

    private static boolean parseBool(Properties props, String key, boolean def) {
        String raw = props.getProperty(key);
        return raw == null ? def : Boolean.parseBoolean(raw.trim());
    }

    private static int parseInt(Properties props, String key, int def) {
        String raw = props.getProperty(key);
        if (raw == null) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String parseString(Properties props, String key, String def) {
        String raw = props.getProperty(key);
        return raw == null ? def : raw.trim();
    }
}
