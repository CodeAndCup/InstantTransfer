package fr.cupcode.instanttransfer.agent.utils;

public class ConfigValue {
    private static boolean debug = false;
    public static final long FALL_GRACE_TICKS = 20L;
    private static String coordHmacSecret = "";

    public static void setDebug(boolean debug) {
        ConfigValue.debug = debug;
    }
    public static boolean isDebug() {
        return debug;
    }

    public static long getFallGraceTicks() {
        return FALL_GRACE_TICKS;
    }

    public static void setCoordHmacSecret(String secret) {
        ConfigValue.coordHmacSecret = secret;
    }
    public static String getCoordHmacSecret() {
        return coordHmacSecret;
    }
}
