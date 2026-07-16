package fr.cupcode.instanttransfer.switching;

import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player mirror of what the client currently believes about the world, so that on a seamless
 * switch (the patched Velocity sends no JoinGame/Respawn) we can tear the old world down cleanly
 * and freeze the player until the arrival chunk is delivered.
 *
 * <p>All fields are touched from the player's netty event-loop thread (packet events are
 * sequential per connection) except {@link #guardActive}, which is armed from a Velocity
 * event thread — hence {@code volatile}.</p>
 */
public final class SwitchState {

    /** Chunks the client currently has loaded. Key = packed (x,z). */
    private final Set<Long> loadedChunks = ConcurrentHashMap.newKeySet();

    /** Entities the client currently has spawned (backend ids). */
    private final Set<Integer> trackedEntities = ConcurrentHashMap.newKeySet();

    /** Snapshot of the previous server's entities/chunks, captured when a switch starts. */
    private final Set<Integer> ghostEntities = ConcurrentHashMap.newKeySet();
    private final Set<Long> ghostChunks = ConcurrentHashMap.newKeySet();

    /**
     * Potion effects the client currently displays on ITSELF (keyed by resource name). Without a
     * Respawn nothing clears them on a switch, so effects unknown to the new server would linger
     * visually (HUD icons/particles) — they are snapshot as ghosts and removed on arrival.
     */
    private final Map<String, PotionType> activeEffects = new ConcurrentHashMap<>();
    private final Map<String, PotionType> ghostEffects = new ConcurrentHashMap<>();

    /**
     * Item cooldowns the client currently displays (SET_COOLDOWN, keyed by cooldown group — the
     * item name pre-1.21.2). A fresh join never carries cooldowns over (the new server starts from
     * zero and vanilla doesn't resend them), so the old server's overlay would linger on the same
     * item type in the new inventory — snapshot as ghosts, zeroed on arrival. Entries whose
     * client-side countdown already expired just make the zeroing a no-op.
     */
    private final Map<String, ResourceLocation> activeCooldowns = new ConcurrentHashMap<>();
    private final Map<String, ResourceLocation> ghostCooldowns = new ConcurrentHashMap<>();

    /**
     * Container window the client currently has open (chest, furnace, horse…), -1 if none. Without
     * a Respawn nothing closes it on a switch, so the old server's GUI would stay open on the new
     * server (and clicks would target a window id the new server never assigned). Snapshot as a
     * ghost and closed on arrival. (The player's own inventory screen sends no open packet and
     * needs no cleanup: the new server resyncs container 0 on join.)
     */
    private volatile int openWindowId = -1;
    private volatile int ghostWindowId = -1;

    /**
     * Weather the client currently displays (rain flag + gradual rain/thunder levels, all driven
     * by CHANGE_GAME_STATE). A new server that is NOT raining sends no weather packet at all on
     * join, so the old server's rain would keep falling forever — snapshot it as a ghost and
     * clear it on arrival unless the new server sent any weather of its own.
     */
    private volatile boolean raining = false;
    private volatile float rainLevel = 0f;
    private volatile float thunderLevel = 0f;
    private volatile boolean ghostWeather = false;

    // --- Arrival guard: between ServerPreConnect and the arrival chunk being delivered. ---

    /** Armed by {@link SwitchManager} when a backend-to-backend switch begins. */
    private volatile boolean guardActive = false;

    /** Wall-clock time the guard was armed / the teleport was seen, for the timeout safety net. */
    private volatile long guardArmedAt = 0L;

    /** True once the arrival PLAYER_POSITION_AND_LOOK has been seen (and the freeze injected). */
    private boolean teleportSeen = false;

    /** Arrival chunk, valid only if {@link #targetChunkKnown}. */
    private boolean targetChunkKnown = false;
    private int targetChunkX, targetChunkZ;

    /** True while we injected client-side flight to prevent falling/sinking during the chunk gap. */
    private boolean frozeFlight = false;

    /**
     * False from the moment a switch is armed until the patched Velocity's synthetic ids message
     * arrives — that message is the FIRST packet ever relayed from the new backend, so anything
     * seen before it during a switch is late old-server traffic (a rain ramp broadcasts
     * RAIN_LEVEL_CHANGE every tick, a moving player still receives chunks…) and must keep
     * counting as ghost state instead of being mistaken for the new server's authority.
     */
    private volatile boolean newServerTalking = true;

    /**
     * True while WE are sending packets from inside the listener; the re-entrant
     * onPacketSend calls for those packets must be ignored (this is what caused the
     * infinite "hold timeout" recursion in the previous position-hold attempt).
     */
    private boolean suppressListener = false;

    // --- Last abilities packet seen from the backend, replayed to un-freeze the player. ---
    private boolean abilitiesKnown = false;
    private boolean abGodMode, abFlying, abFlightAllowed, abCreative;
    private float abFlySpeed = 0.05f, abFovModifier = 0.1f;

    // --- Entity-id remap (Velocity #251): the client keeps its ORIGINAL entity id forever (it never
    //     receives another JoinGame), while each backend assigns its own. The patched Velocity sends
    //     both ids on the "instanttransfer:ids" channel at every seamless switch; the remapper then
    //     SWAPS the two ids in entity packets both ways (swap also shields the reverse collision:
    //     a new-server entity that happens to use the client's id). ---
    private volatile int clientEntityId = -1;
    private volatile int backendEntityId = -1;

    /**
     * The new backend's gamemode, announced with the ids message. Normally the client adopts it
     * from JoinGame/Respawn — which a seamless switch never sends — so a player whose gamemode
     * differs between backends would keep the stale one (breaking eating, flight, block breaking…).
     * Applied via a ChangeGameState packet on arrival, then reset to -1.
     */
    private volatile int pendingGamemode = -1;

    public void armGuard(long nowMillis) {
        teleportSeen = false;
        targetChunkKnown = false;
        frozeFlight = false;
        newServerTalking = false;
        guardArmedAt = nowMillis;
        guardActive = true;
    }

    /** True while switch traffic can still be from the OLD server (before the ids message). */
    public boolean oldServerTalking() {
        return guardActive && !newServerTalking;
    }

    public void markNewServerTalking() {
        newServerTalking = true;
    }

    public boolean guardActive() {
        return guardActive;
    }

    /**
     * Atomically finish the guard; returns true only for the first caller. Everything the release
     * does (send packets, log) must happen AFTER this flips, so re-entrant packet events are no-ops.
     */
    public boolean finishGuard() {
        if (!guardActive) {
            return false;
        }
        guardActive = false;
        return true;
    }

    public long guardArmedAt() {
        return guardArmedAt;
    }

    public boolean teleportSeen() {
        return teleportSeen;
    }

    public void markTeleportSeen(long nowMillis) {
        teleportSeen = true;
        guardArmedAt = nowMillis; // restart the timeout window from the teleport
    }

    public boolean targetChunkKnown() {
        return targetChunkKnown;
    }

    public void targetChunk(int x, int z) {
        targetChunkX = x;
        targetChunkZ = z;
        targetChunkKnown = true;
    }

    public int targetChunkX() {
        return targetChunkX;
    }

    public int targetChunkZ() {
        return targetChunkZ;
    }

    public boolean frozeFlight() {
        return frozeFlight;
    }

    public void frozeFlight(boolean value) {
        frozeFlight = value;
    }

    public boolean suppressListener() {
        return suppressListener;
    }

    public void suppressListener(boolean value) {
        suppressListener = value;
    }

    public boolean abilitiesKnown() {
        return abilitiesKnown;
    }

    public void rememberAbilities(boolean godMode, boolean flying, boolean flightAllowed,
                                  boolean creative, float flySpeed, float fovModifier) {
        this.abGodMode = godMode;
        this.abFlying = flying;
        this.abFlightAllowed = flightAllowed;
        this.abCreative = creative;
        this.abFlySpeed = flySpeed;
        this.abFovModifier = fovModifier;
        this.abilitiesKnown = true;
    }

    public boolean abGodMode() {
        return abGodMode;
    }

    public boolean abFlying() {
        return abFlying;
    }

    public boolean abFlightAllowed() {
        return abFlightAllowed;
    }

    public boolean abCreative() {
        return abCreative;
    }

    public float abFlySpeed() {
        return abFlySpeed;
    }

    public float abFovModifier() {
        return abFovModifier;
    }

    public void entityIds(int clientId, int backendId) {
        this.clientEntityId = clientId;
        this.backendEntityId = backendId;
    }

    public int pendingGamemode() {
        return pendingGamemode;
    }

    public void pendingGamemode(int gamemode) {
        this.pendingGamemode = gamemode;
    }

    public int clientEntityId() {
        return clientEntityId;
    }

    public int backendEntityId() {
        return backendEntityId;
    }

    /** True when the client's and the current backend's player entity ids differ. */
    public boolean remapActive() {
        return clientEntityId >= 0 && backendEntityId >= 0 && clientEntityId != backendEntityId;
    }

    /** Symmetric swap of the client/backend player entity ids; any other id passes through. */
    public int swapEntityId(int id) {
        if (id == backendEntityId) {
            return clientEntityId;
        }
        if (id == clientEntityId) {
            return backendEntityId;
        }
        return id;
    }

    public Set<Long> loadedChunks() {
        return loadedChunks;
    }

    public Set<Integer> trackedEntities() {
        return trackedEntities;
    }

    public Set<Integer> ghostEntities() {
        return ghostEntities;
    }

    public Set<Long> ghostChunks() {
        return ghostChunks;
    }

    public Map<String, PotionType> activeEffects() {
        return activeEffects;
    }

    public Map<String, PotionType> ghostEffects() {
        return ghostEffects;
    }

    public Map<String, ResourceLocation> activeCooldowns() {
        return activeCooldowns;
    }

    public Map<String, ResourceLocation> ghostCooldowns() {
        return ghostCooldowns;
    }

    public int openWindowId() {
        return openWindowId;
    }

    public void openWindowId(int windowId) {
        this.openWindowId = windowId;
    }

    public int ghostWindowId() {
        return ghostWindowId;
    }

    public void ghostWindowId(int windowId) {
        this.ghostWindowId = windowId;
    }

    public void raining(boolean value) {
        this.raining = value;
    }

    public void rainLevel(float value) {
        this.rainLevel = value;
    }

    public void thunderLevel(float value) {
        this.thunderLevel = value;
    }

    /** True if the client currently renders any weather (rain flag or a non-zero level). */
    public boolean weatherActive() {
        return raining || rainLevel > 0f || thunderLevel > 0f;
    }

    public void clearWeather() {
        raining = false;
        rainLevel = 0f;
        thunderLevel = 0f;
    }

    public boolean ghostWeather() {
        return ghostWeather;
    }

    public void ghostWeather(boolean value) {
        this.ghostWeather = value;
    }

    /** Capture the current world as "ghosts" to be removed once the seamless switch completes. */
    public void snapshotGhosts() {
        ghostEntities.clear();
        ghostEntities.addAll(trackedEntities);
        ghostChunks.clear();
        ghostChunks.addAll(loadedChunks);
        ghostEffects.clear();
        ghostEffects.putAll(activeEffects);
        ghostCooldowns.clear();
        ghostCooldowns.putAll(activeCooldowns);
        ghostWindowId = openWindowId;
        ghostWeather = weatherActive();
    }

    public static long chunkKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) | ((long) z << 32);
    }

    public static int chunkX(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }

    public static int chunkZ(long key) {
        return (int) (key >> 32);
    }
}
