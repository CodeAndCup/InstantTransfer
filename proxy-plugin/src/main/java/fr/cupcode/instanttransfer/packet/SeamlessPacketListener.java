package fr.cupcode.instanttransfer.packet;

import fr.cupcode.instanttransfer.config.PluginConfig;
import fr.cupcode.instanttransfer.coord.CoordManager;
import fr.cupcode.instanttransfer.switching.SwitchManager;
import fr.cupcode.instanttransfer.switching.SwitchState;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client-side half of the zero-screen switch (the Velocity core patch removes the config phase
 * and sends no JoinGame/Respawn). This listener:
 * <ol>
 *   <li>tracks the entities and chunks the client currently has (plus the backend's last abilities
 *       packet);</li>
 *   <li>on a switch, lets the arrival teleport PASS (so the backend's teleport-confirm handshake
 *       stays intact) but immediately injects a client-side flight freeze, so the player hovers in
 *       place instead of falling/sinking while the arrival chunk is still loading;</li>
 *   <li>once the arrival chunk is delivered (or on timeout), restores the backend's real abilities
 *       and removes the previous server's entities and chunks (ghost cleanup).</li>
 * </ol>
 *
 * <p>Design notes, learned the hard way:</p>
 * <ul>
 *   <li>NEVER cancel the arrival PLAYER_POSITION_AND_LOOK: Paper waits for the client's
 *       teleport-confirm before proceeding, so withholding it deadlocks/rubber-bands the join.</li>
 *   <li>{@code user.sendPacket(...)} re-enters this listener synchronously; every packet we send is
 *       wrapped in {@link SwitchState#suppressListener(boolean)} and the release is gated by an
 *       atomic {@link SwitchState#finishGuard()} — otherwise the release recurses infinitely (the
 *       "hold timeout" log spam of the previous attempt).</li>
 *   <li>Old-server chunks may cover the SAME coordinates as arrival chunks (overlapping spawn
 *       areas); a chunk key counts as "arrived" only if it is not still a ghost. Any CHUNK_DATA or
 *       SPAWN_ENTITY from the new server also un-ghosts its key/id, so cleanup never destroys new
 *       content.</li>
 * </ul>
 */
public final class SeamlessPacketListener implements PacketListener {

    /** Safety net: release the freeze this long after the arrival teleport even without the chunk. */
    private static final long GUARD_TIMEOUT_MS = 3000L;

    /** PLAYER_POSITION_AND_LOOK relative-flag bits for X and Z. */
    private static final byte REL_X = 0x01;
    private static final byte REL_Z = 0x04;

    private final SwitchManager manager;
    private final CoordManager coord;
    private final PluginConfig config;
    private final Logger logger;

    public SeamlessPacketListener(SwitchManager manager, CoordManager coord,
                                  PluginConfig config, Logger logger) {
        this.manager = manager;
        this.coord = coord;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        SwitchState state = manager.getOrCreate(user.getUUID());
        if (state.suppressListener()) {
            return; // one of OUR injected packets re-entering the pipeline
        }
        PacketTypeCommon type = event.getPacketType();

        try {
            if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                int id = new WrapperPlayServerSpawnEntity(event).getEntityId();
                state.trackedEntities().add(id);
                state.ghostEntities().remove(id); // new backend reused an old id: not a ghost anymore

            } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
                for (int id : new WrapperPlayServerDestroyEntities(event).getEntityIds()) {
                    state.trackedEntities().remove(id);
                    state.ghostEntities().remove(id);
                }

            } else if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
                WrapperPlayServerUnloadChunk w = new WrapperPlayServerUnloadChunk(event);
                long key = SwitchState.chunkKey(w.getChunkX(), w.getChunkZ());
                state.loadedChunks().remove(key);
                state.ghostChunks().remove(key);

            } else if (type == PacketType.Play.Server.CHUNK_DATA) {
                WrapperPlayServerChunkData w = new WrapperPlayServerChunkData(event);
                int cx = w.getColumn().getX();
                int cz = w.getColumn().getZ();
                long key = SwitchState.chunkKey(cx, cz);
                state.loadedChunks().add(key);
                state.ghostChunks().remove(key); // fresh data replaces any old-server chunk here
                if (state.guardActive() && state.teleportSeen() && state.targetChunkKnown()
                        && cx == state.targetChunkX() && cz == state.targetChunkZ()) {
                    release(user, state, "arrival chunk delivered");
                    return;
                }

            } else if (type == PacketType.Play.Server.JOIN_GAME) {
                // Only the very FIRST login's JoinGame ever reaches the client (seamless switches
                // forward none); it defines the entity id the client keeps for its whole session.
                int id = new WrapperPlayServerJoinGame(event).getEntityId();
                state.entityIds(id, id);

            } else if (type == PacketType.Play.Server.ENTITY_EFFECT) {
                // Runs after the remapper, so the id is client-space: self-effects only.
                WrapperPlayServerEntityEffect w = new WrapperPlayServerEntityEffect(event);
                if (w.getEntityId() == state.clientEntityId() && w.getPotionType() != null) {
                    String key = w.getPotionType().getName().toString();
                    state.activeEffects().put(key, w.getPotionType());
                    state.ghostEffects().remove(key); // the new server (re-)applied it: not a ghost
                }

            } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
                WrapperPlayServerRemoveEntityEffect w = new WrapperPlayServerRemoveEntityEffect(event);
                if (w.getEntityId() == state.clientEntityId() && w.getPotionType() != null) {
                    String key = w.getPotionType().getName().toString();
                    state.activeEffects().remove(key);
                    state.ghostEffects().remove(key);
                }

            } else if (type == PacketType.Play.Server.PLAYER_ABILITIES) {
                WrapperPlayServerPlayerAbilities w = new WrapperPlayServerPlayerAbilities(event);
                state.rememberAbilities(w.isInGodMode(), w.isFlying(), w.isFlightAllowed(),
                        w.isInCreativeMode(), w.getFlySpeed(), w.getFOVModifier());

            } else if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK
                    && state.guardActive() && !state.teleportSeen()) {
                onArrivalTeleport(event, user, state);
                return;
            }

            // Safety net: never leave the player frozen/ghosted if the target chunk never matches.
            if (state.guardActive() && state.teleportSeen()
                    && System.currentTimeMillis() - state.guardArmedAt() > GUARD_TIMEOUT_MS) {
                release(user, state, "timeout");
            }
        } catch (Exception e) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] listener error on {}: {}", type, e.toString());
            }
        }
    }

    /**
     * The new backend's arrival teleport. It is FORWARDED untouched; we only note the landing chunk
     * and freeze the client (flight on) so it hovers until that chunk is delivered.
     */
    private void onArrivalTeleport(PacketSendEvent event, User user, SwitchState state) {
        WrapperPlayServerPlayerPositionAndLook w = new WrapperPlayServerPlayerPositionAndLook(event);
        state.markTeleportSeen(System.currentTimeMillis());

        byte mask = w.getRelativeMask();
        boolean absolute = (mask & (REL_X | REL_Z)) == 0;
        if (absolute) {
            int cx = (int) Math.floor(w.getX()) >> 4;
            int cz = (int) Math.floor(w.getZ()) >> 4;
            state.targetChunk(cx, cz);
            long key = SwitchState.chunkKey(cx, cz);
            // Already have the REAL new chunk (preloaded fast path)? Then no freeze is needed.
            if (state.loadedChunks().contains(key) && !state.ghostChunks().contains(key)) {
                release(user, state, "arrival chunk already loaded");
                return;
            }
        }
        // Freeze: hover in place instead of falling into not-yet-loaded ground. The backend agent
        // grants allowFlight server-side during the arrival grace so this cannot kick the player.
        sendSuppressed(user, state, new WrapperPlayServerPlayerAbilities(
                state.abGodMode(), true, true, state.abCreative(),
                state.abFlySpeed(), state.abFovModifier()));
        state.frozeFlight(true);
        if (config.debug()) {
            logger.info("[InstantTransfer] arrival teleport for {}: target chunk {} — freeze on",
                    user.getName(),
                    absolute ? "(" + state.targetChunkX() + "," + state.targetChunkZ() + ")"
                             : "unknown (relative coords)");
        }
    }

    /** Finish the switch exactly once: un-freeze, then remove the previous server's ghosts. */
    private void release(User user, SwitchState state, String reason) {
        if (!state.finishGuard()) {
            return;
        }
        if (state.frozeFlight()) {
            // End the hover NOW (flying=false: gravity resumes onto the freshly loaded ground).
            sendSuppressed(user, state, new WrapperPlayServerPlayerAbilities(
                    state.abGodMode(), false, state.abFlightAllowed(),
                    state.abCreative(), state.abFlySpeed(), state.abFovModifier()));
            state.frozeFlight(false);
        }
        // The gamemode is normally adopted from JoinGame/Respawn, which a seamless switch never
        // sends: if it differs between backends (e.g. /gamemode creative on one of them), the stale
        // client gamemode desyncs eating (infinite chewing), flight and block breaking. Resync it
        // AFTER the abilities restore (a gamemode change resets client abilities).
        int gamemode = state.pendingGamemode();
        if (gamemode >= 0) {
            sendSuppressed(user, state, new WrapperPlayServerChangeGameState(
                    WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, gamemode));
            state.pendingGamemode(-1);
        }

        // The agent granted allowFlight for its whole grace window (so the hover can never kick);
        // tell it the arrival is over so it restores the real flight permission immediately —
        // otherwise the player can fly for the rest of the grace and gets rolled back at its end.
        coord.sendUnfreeze(user.getUUID());

        Set<Integer> ghostEntities = state.ghostEntities();
        Set<Long> ghostChunks = state.ghostChunks();
        int entityCount = ghostEntities.size();
        if (entityCount > 0) {
            int[] ids = ghostEntities.stream().mapToInt(Integer::intValue).toArray();
            sendSuppressed(user, state, new WrapperPlayServerDestroyEntities(ids));
            state.trackedEntities().removeAll(ghostEntities);
        }
        int chunkCount = ghostChunks.size();
        for (long key : ghostChunks) {
            sendSuppressed(user, state,
                    new WrapperPlayServerUnloadChunk(SwitchState.chunkX(key), SwitchState.chunkZ(key)));
        }
        state.loadedChunks().removeAll(ghostChunks);
        ghostEntities.clear();
        ghostChunks.clear();

        // Effects from the previous server the new one did not re-apply: clear their client display.
        Map<String, PotionType> ghostEffects = state.ghostEffects();
        int effectCount = ghostEffects.size();
        if (effectCount > 0 && state.clientEntityId() >= 0) {
            for (PotionType effect : ghostEffects.values()) {
                sendSuppressed(user, state,
                        new WrapperPlayServerRemoveEntityEffect(state.clientEntityId(), effect));
            }
            state.activeEffects().keySet().removeAll(ghostEffects.keySet());
            // The swirl PARTICLES around the player come from the entity's metadata, not from the
            // effect list — and the new server only sends non-default metadata, so a "no effects"
            // state never overwrites the old swirls. Reset the living-entity effect fields
            // explicitly (1.20.5+: index 10 = effect particles list, 11 = ambient flag).
            if (user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_20_5)) {
                sendSuppressed(user, state, new WrapperPlayServerEntityMetadata(
                        state.clientEntityId(), List.of(
                                new EntityData<>(10, EntityDataTypes.PARTICLES, List.of()),
                                new EntityData<>(11, EntityDataTypes.BOOLEAN, false))));
            } else {
                // Pre-1.20.5: index 10 = potion effect color (0 = none), 11 = ambient flag.
                sendSuppressed(user, state, new WrapperPlayServerEntityMetadata(
                        state.clientEntityId(), List.of(
                                new EntityData<>(10, EntityDataTypes.INT, 0),
                                new EntityData<>(11, EntityDataTypes.BOOLEAN, false))));
            }
        }
        ghostEffects.clear();

        logger.info("[InstantTransfer] seamless arrival for {} ({}): removed {} ghost entities, "
                        + "{} ghost chunks, {} ghost effects",
                user.getName(), reason, entityCount, chunkCount, effectCount);
    }

    /** Send a packet of ours without re-triggering this listener (see class javadoc). */
    private void sendSuppressed(User user, SwitchState state,
                                com.github.retrooper.packetevents.wrapper.PacketWrapper<?> packet) {
        state.suppressListener(true);
        try {
            user.sendPacket(packet);
        } finally {
            state.suppressListener(false);
        }
    }
}
