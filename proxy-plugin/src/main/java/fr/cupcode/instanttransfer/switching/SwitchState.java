package fr.cupcode.instanttransfer.switching;

import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import fr.cupcode.instanttransfer.protocol.ProtocolAdapter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Byte offset of the [int chunkX][int chunkZ] header inside a raw CHUNK_DATA body (0, or 1
     * when packetevents exposes the body with the leading packet-id VarInt). Probed once on the
     * first chunk of a connection, then reused; null until probed.
     */
    private volatile Integer chunkHeaderOffsetCache;

    /** Monotonic index for the debug-only peek-vs-wrapper verification (first N chunks only). */
    private final AtomicInteger chunkPeekChecks = new AtomicInteger();

    /**
     * Byte offset of the leading entity-id VarInt inside raw movement-packet bodies (0 once
     * proven, -1 when peeking proved unusable for this connection). Probed once on the first
     * movement packet of a connection, then reused; null until probed.
     */
    private volatile Integer movementHeaderOffsetCache;

    /** Monotonic index for the debug-only peek-vs-wrapper verification (first N movement packets). */
    private final AtomicInteger movementPeekChecks = new AtomicInteger();

    /** Entities the client currently has spawned (backend ids). */
    private final Set<Integer> trackedEntities = ConcurrentHashMap.newKeySet();

    /** Snapshot of the previous server's entities/chunks, captured when a switch starts. */
    private final Set<Integer> ghostEntities = ConcurrentHashMap.newKeySet();
    private final Set<Long> ghostChunks = ConcurrentHashMap.newKeySet();

    private final PendingChunkUnloads pendingUnloads = new PendingChunkUnloads();

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
     * Scoreboard objectives and teams the client currently knows. Without a Respawn nothing tears
     * down the old server's sidebar/objectives (their scores and display slots die with the
     * objective) or teams (name colors, prefixes, collision rules), so any the new server does
     * not re-create are snapshot as ghosts and removed on arrival.
     */
    private final Set<String> activeObjectives = ConcurrentHashMap.newKeySet();
    private final Set<String> ghostObjectives = ConcurrentHashMap.newKeySet();
    private final Set<String> activeTeams = ConcurrentHashMap.newKeySet();
    private final Set<String> ghostTeams = ConcurrentHashMap.newKeySet();

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

    /** Arrival position (from the teleport packet), stored for timeout position correction. */
    private double arrivalX, arrivalY, arrivalZ;
    private float arrivalYaw, arrivalPitch;
    private boolean arrivalPositionKnown = false;
    private boolean arrivalPositionAbsolute = false;

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
     * True once the client received its OWN tab-list entry (first login's ADD_PLAYER). The client
     * caches that PlayerInfo object inside the player entity, and without JoinGame/Respawn the
     * entity — and its cache — live forever. A remove/re-add cycle (which Velocity + the new
     * backend perform on every switch) would replace the map object while the entity keeps the
     * stale one: gamemode updates then land on the new object and isSpectator()/isCreative()
     * keep reading the old — spectator noclip breaks. So after the first add, the own entry is
     * never removed and re-adds are downgraded to in-place updates.
     */
    private volatile boolean ownTabEntryAdded = false;

    /**
     * Dimension type key of the client's current level (from the first JoinGame, then every
     * Respawn). Lets the listener tell a same-dimension Respawn (client KEEPS its level — chunks
     * and entities survive) from a cross-dimension one (level rebuilt from scratch — the patched
     * Velocity's fallback for cross-dimension switches, or a nether→overworld death respawn).
     */
    private volatile String clientDimension = null;

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

    /** Lazily resolved from the user's ClientVersion; stable for the connection's lifetime. */
    private volatile ProtocolAdapter adapter;

    public ProtocolAdapter adapter(java.util.function.Supplier<ProtocolAdapter> resolver) {
        ProtocolAdapter a = adapter;
        if (a == null) {
            a = resolver.get();
            adapter = a;
        }
        return a;
    }

    public ProtocolAdapter adapterIfResolved() { return adapter; }

    public void armGuard(long nowMillis) {
        teleportSeen = false;
        targetChunkKnown = false;
        arrivalPositionKnown = false;
        arrivalPositionAbsolute = false;
        frozeFlight = false;
        newServerTalking = false;
        guardArmedAt = nowMillis;
        guardActive = true;
    }

    /** Atomically snapshot the relevant SwitchState fields; caller must not rely on separate
     *  volatile reads (TOCTOU guard). */
    public SwitchStateSnapshot snapshot() {
        return new SwitchStateSnapshot(
                guardActive, newServerTalking, guardArmedAt,
                teleportSeen, targetChunkKnown,
                arrivalPositionKnown, arrivalPositionAbsolute,
                frozeFlight, clientEntityId, backendEntityId,
                pendingGamemode, ownTabEntryAdded,
                raining, rainLevel, thunderLevel, ghostWeather,
                suppressListener, abilitiesKnown,
                abGodMode, abFlying, abFlightAllowed, abCreative,
                abFlySpeed, abFovModifier,
                clientDimension);
    }

    /** Immutable snapshot of the most critical SwitchState fields, read atomically to prevent
     *  TOCTOU races between compound volatile checks (e.g. guardActive + newServerTalking +
     *  guardArmedAt timeout). */
    public static final class SwitchStateSnapshot {
        public final boolean guardActive;
        public final boolean newServerTalking;
        public final long guardArmedAt;
        public final boolean teleportSeen;
        public final boolean targetChunkKnown;
        public final boolean arrivalPositionKnown;
        public final boolean arrivalPositionAbsolute;
        public final boolean frozeFlight;
        public final int clientEntityId;
        public final int backendEntityId;
        public final int pendingGamemode;
        public final boolean ownTabEntryAdded;
        public final boolean raining;
        public final float rainLevel;
        public final float thunderLevel;
        public final boolean ghostWeather;
        public final boolean suppressListener;
        public final boolean abilitiesKnown;
        public final boolean abGodMode;
        public final boolean abFlying;
        public final boolean abFlightAllowed;
        public final boolean abCreative;
        public final float abFlySpeed;
        public final float abFovModifier;
        public final String clientDimension;

        public SwitchStateSnapshot(boolean guardActive, boolean newServerTalking,
                long guardArmedAt, boolean teleportSeen, boolean targetChunkKnown,
                boolean arrivalPositionKnown, boolean arrivalPositionAbsolute,
                boolean frozeFlight, int clientEntityId, int backendEntityId,
                int pendingGamemode, boolean ownTabEntryAdded,
                boolean raining, float rainLevel, float thunderLevel,
                boolean ghostWeather, boolean suppressListener,
                boolean abilitiesKnown, boolean abGodMode, boolean abFlying,
                boolean abFlightAllowed, boolean abCreative,
                float abFlySpeed, float abFovModifier, String clientDimension) {
            this.guardActive = guardActive;
            this.newServerTalking = newServerTalking;
            this.guardArmedAt = guardArmedAt;
            this.teleportSeen = teleportSeen;
            this.targetChunkKnown = targetChunkKnown;
            this.arrivalPositionKnown = arrivalPositionKnown;
            this.arrivalPositionAbsolute = arrivalPositionAbsolute;
            this.frozeFlight = frozeFlight;
            this.clientEntityId = clientEntityId;
            this.backendEntityId = backendEntityId;
            this.pendingGamemode = pendingGamemode;
            this.ownTabEntryAdded = ownTabEntryAdded;
            this.raining = raining;
            this.rainLevel = rainLevel;
            this.thunderLevel = thunderLevel;
            this.ghostWeather = ghostWeather;
            this.suppressListener = suppressListener;
            this.abilitiesKnown = abilitiesKnown;
            this.abGodMode = abGodMode;
            this.abFlying = abFlying;
            this.abFlightAllowed = abFlightAllowed;
            this.abCreative = abCreative;
            this.abFlySpeed = abFlySpeed;
            this.abFovModifier = abFovModifier;
            this.clientDimension = clientDimension;
        }
    }

    /** True while switch traffic can still be from the OLD server (before the ids message). */
    public boolean oldServerTalking() {
        // Atomic read via snapshot to prevent TOCTOU
        SwitchStateSnapshot s = snapshot();
        return s.guardActive && !s.newServerTalking;
    }

    public void markNewServerTalking() {
        newServerTalking = true;
    }

    public boolean ownTabEntryAdded() {
        return ownTabEntryAdded;
    }

    public void markOwnTabEntryAdded() {
        ownTabEntryAdded = true;
    }

    public String clientDimension() {
        return clientDimension;
    }

    public void clientDimension(String dimension) {
        this.clientDimension = dimension;
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

    public boolean arrivalPositionKnown() {
        return arrivalPositionKnown;
    }

    public boolean arrivalPositionAbsolute() {
        return arrivalPositionAbsolute;
    }

    public void arrivalPosition(double x, double y, double z, float yaw, float pitch, boolean absolute) {
        this.arrivalX = x;
        this.arrivalY = y;
        this.arrivalZ = z;
        this.arrivalYaw = yaw;
        this.arrivalPitch = pitch;
        this.arrivalPositionKnown = true;
        this.arrivalPositionAbsolute = absolute;
    }

    public double arrivalX() {
        return arrivalX;
    }

    public double arrivalY() {
        return arrivalY;
    }

    public double arrivalZ() {
        return arrivalZ;
    }

    public float arrivalYaw() {
        return arrivalYaw;
    }

    public float arrivalPitch() {
        return arrivalPitch;
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

    public Integer chunkHeaderOffsetCache() {
        return chunkHeaderOffsetCache;
    }

    public void chunkHeaderOffsetCache(int v) {
        this.chunkHeaderOffsetCache = v;
    }

    public int nextChunkPeekCheck() {
        return chunkPeekChecks.getAndIncrement();
    }

    public Integer movementHeaderOffsetCache() { return movementHeaderOffsetCache; }
    public void movementHeaderOffsetCache(int v) { this.movementHeaderOffsetCache = v; }

    public int nextMovementPeekCheck() {
        return movementPeekChecks.getAndIncrement();
    }

    /** Re-arms the first-N raw-peek sanity probes for a new switch (they stop after 10 packets). */
    public void resetPeekProbes() {
        chunkPeekChecks.set(0);
        movementPeekChecks.set(0);
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

    public PendingChunkUnloads pendingChunkUnloads() {
        return pendingUnloads;
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

    public Set<String> activeObjectives() {
        return activeObjectives;
    }

    public Set<String> ghostObjectives() {
        return ghostObjectives;
    }

    public Set<String> activeTeams() {
        return activeTeams;
    }

    public Set<String> ghostTeams() {
        return ghostTeams;
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
    public void snapshotGhosts() {        ghostEntities.clear();
        ghostEntities.addAll(trackedEntities);
        ghostChunks.clear();
        ghostChunks.addAll(loadedChunks);
        ghostEffects.clear();
        ghostEffects.putAll(activeEffects);
        ghostCooldowns.clear();
        ghostCooldowns.putAll(activeCooldowns);
        ghostObjectives.clear();
        ghostObjectives.addAll(activeObjectives);
        ghostTeams.clear();
        ghostTeams.addAll(activeTeams);
        ghostWindowId = openWindowId;
        ghostWeather = weatherActive();
        // Defer every ghost chunk's UnloadChunk NOW, before the new server's resend stream starts:
        // its CHUNK_DATA/UNLOAD_CHUNK traffic during the guard cancels matching keys, and only the
        // uncanceled remainder is flushed when the guard ends.
        for (Long key : ghostChunks) {
            pendingUnloads.defer(key);
        }
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

    /**
     * Ghost chunks whose UnloadChunk is postponed until the arrival guard ends. Keys are deferred
     * when the switch arms ({@link #snapshotGhosts()}, plus any late old-server chunk) — BEFORE
     * the new server's resend stream starts; every CHUNK_DATA/UNLOAD_CHUNK the destination sends
     * during the guard cancels its key, so only genuinely stale chunks get an unload packet at
     * guard completion or timeout (hundreds fewer netty writes per switch). Thread-safety:
     * synchronized methods — snapshot/defer runs on a Velocity event thread while CHUNK_DATA
     * arrives on the connection thread.
     */
    public static final class PendingChunkUnloads {
        private final java.util.Set<Long> pending = new java.util.HashSet<>();

        public synchronized void defer(long key) { pending.add(key); }
        public synchronized void cancelIfPresent(long key) { pending.remove(key); }
        public synchronized boolean isEmpty() { return pending.isEmpty(); }
        public synchronized void clear() { pending.clear(); }
        public synchronized long[] drainSorted() {
            long[] out = new long[pending.size()];
            int i = 0;
            for (Long k : pending) out[i++] = k;
            pending.clear();
            java.util.Arrays.sort(out);
            return out;
        }
    }
}
