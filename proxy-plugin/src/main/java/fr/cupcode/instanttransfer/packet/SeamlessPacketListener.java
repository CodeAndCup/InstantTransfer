package fr.cupcode.instanttransfer.packet;

import fr.cupcode.instanttransfer.InstantTransfer;
import fr.cupcode.instanttransfer.config.PluginConfig;
import fr.cupcode.instanttransfer.coord.CoordManager;
import fr.cupcode.instanttransfer.protocol.ProtocolAdapter;
import fr.cupcode.instanttransfer.switching.SwitchManager;
import fr.cupcode.instanttransfer.switching.SwitchState;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.protocol.world.dimension.DimensionTypeRef;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenHorseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRespawn;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCooldown;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 *   <li>The OLD server keeps sending packets between the ghost snapshot and the actual backend
 *       swap (a rain ramp broadcasts RAIN_LEVEL_CHANGE every tick, a moving player still gets
 *       chunks). Anything ADDING client state in that window must go INTO the ghosts, not clear
 *       them; only traffic after the ids message ({@link SwitchState#oldServerTalking()} false)
 *       is the new server's. Removals are safe from either side.</li>
 * </ul>
 */
public final class SeamlessPacketListener implements PacketListener {

    /** PLAYER_POSITION_AND_LOOK relative-flag bits for X and Z. */
    private static final byte REL_X = 0x01;
    private static final byte REL_Z = 0x04;

    private final SwitchManager manager;
    private final CoordManager coord;
    private final PluginConfig config;
    private final Logger logger;
    private final InstantTransfer plugin;

    public SeamlessPacketListener(SwitchManager manager, CoordManager coord,
                                  PluginConfig config, Logger logger, InstantTransfer plugin) {
        this.manager = manager;
        this.coord = coord;
        this.config = config;
        this.logger = logger;
        this.plugin = plugin;
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
                if (state.oldServerTalking()) {
                    state.ghostEntities().add(id); // late old-server spawn: one more ghost to clean
                } else {
                    state.ghostEntities().remove(id); // new backend reused an old id: not a ghost
                }

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
                if (state.oldServerTalking()) {
                    state.ghostChunks().add(key); // late old-server chunk: one more ghost to clean
                } else {
                    state.ghostChunks().remove(key); // fresh data replaces any old-server chunk here
                }
                if (state.guardActive() && state.teleportSeen() && state.targetChunkKnown()
                        && cx == state.targetChunkX() && cz == state.targetChunkZ()) {
                    release(user, state, "arrival chunk delivered");
                    return;
                }

            } else if (type == PacketType.Play.Server.JOIN_GAME) {
                // Only the very FIRST login's JoinGame ever reaches the client (seamless switches
                // forward none); it defines the entity id the client keeps for its whole session.
                WrapperPlayServerJoinGame w = new WrapperPlayServerJoinGame(event);
                state.entityIds(w.getEntityId(), w.getEntityId());
                state.clientDimension(dimensionKey(w.getDimensionTypeRef(),
                        state.adapter(() -> ProtocolAdapter.forClientVersion(user.getClientVersion()))));
                state.markNewServerTalking(); // safety: patch disabled → vanilla switch path

            } else if (type == PacketType.Play.Server.RESPAWN) {
                onRespawn(event, user, state);

            } else if (type == PacketType.Play.Server.ENTITY_EFFECT) {
                // Runs after the remapper, so the id is client-space: self-effects only.
                WrapperPlayServerEntityEffect w = new WrapperPlayServerEntityEffect(event);
                if (w.getEntityId() == state.clientEntityId() && w.getPotionType() != null) {
                    String key = w.getPotionType().getName().toString();
                    state.activeEffects().put(key, w.getPotionType());
                    if (state.oldServerTalking()) {
                        state.ghostEffects().put(key, w.getPotionType()); // late old-server effect
                    } else {
                        state.ghostEffects().remove(key); // the new server (re-)applied it: not a ghost
                    }
                }

            } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
                WrapperPlayServerRemoveEntityEffect w = new WrapperPlayServerRemoveEntityEffect(event);
                if (w.getEntityId() == state.clientEntityId() && w.getPotionType() != null) {
                    String key = w.getPotionType().getName().toString();
                    state.activeEffects().remove(key);
                    state.ghostEffects().remove(key);
                }

            } else if (type == PacketType.Play.Server.PLAYER_INFO_REMOVE) {
                // The client caches its OWN tab-list PlayerInfo object inside the player entity;
                // without JoinGame/Respawn that entity — and the cache — live forever. If the
                // entry is removed and re-added (Velocity clears the tab on every switch, the new
                // backend re-adds), later gamemode updates land on the REPLACEMENT object while
                // isSpectator()/isCreative() keep reading the stale one → spectator noclip breaks
                // ("creative but invisible"). The own entry must survive the whole session.
                WrapperPlayServerPlayerInfoRemove w = new WrapperPlayServerPlayerInfoRemove(event);
                if (w.getProfileIds().contains(user.getUUID())) {
                    List<UUID> others = new ArrayList<>(w.getProfileIds());
                    others.remove(user.getUUID());
                    if (others.isEmpty()) {
                        event.setCancelled(true);
                    } else {
                        w.setProfileIds(others);
                        event.markForReEncode(true);
                    }
                }

            } else if (type == PacketType.Play.Server.PLAYER_INFO_UPDATE) {
                onPlayerInfoUpdate(event, user, state);

            } else if (type == PacketType.Play.Server.SCOREBOARD_OBJECTIVE) {
                WrapperPlayServerScoreboardObjective w = new WrapperPlayServerScoreboardObjective(event);
                if (w.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.CREATE) {
                    if (state.oldServerTalking()) {
                        state.ghostObjectives().add(w.getName());
                    } else if (state.ghostObjectives().remove(w.getName())) {
                        // The new server re-creates an objective the client still has from the old
                        // one. The vanilla client THROWS on a duplicate objective CREATE ("An
                        // objective with the name ... already exists!" → disconnect), so remove
                        // the stale one first — this injected packet reaches the client BEFORE
                        // the in-flight CREATE.
                        sendSuppressed(user, state, new WrapperPlayServerScoreboardObjective(
                                w.getName(), WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                                null, null));
                    }
                    state.activeObjectives().add(w.getName());
                } else if (w.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
                    state.activeObjectives().remove(w.getName());
                    state.ghostObjectives().remove(w.getName());
                }

            } else if (type == PacketType.Play.Server.TEAMS) {
                WrapperPlayServerTeams w = new WrapperPlayServerTeams(event);
                if (w.getTeamMode() == WrapperPlayServerTeams.TeamMode.CREATE) {
                    if (state.oldServerTalking()) {
                        state.ghostTeams().add(w.getTeamName());
                    } else if (state.ghostTeams().remove(w.getTeamName())) {
                        // Duplicate team CREATE only logs a client warning (the stale team wins);
                        // remove the old one first so the new server's definition applies.
                        sendSuppressed(user, state, new WrapperPlayServerTeams(
                                w.getTeamName(), WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty()));
                    }
                    state.activeTeams().add(w.getTeamName());
                } else if (w.getTeamMode() == WrapperPlayServerTeams.TeamMode.REMOVE) {
                    state.activeTeams().remove(w.getTeamName());
                    state.ghostTeams().remove(w.getTeamName());
                }

            } else if (type == PacketType.Play.Server.SET_COOLDOWN) {
                WrapperPlayServerSetCooldown w = new WrapperPlayServerSetCooldown(event);
                if (config.debug()) {
                    logger.info("[InstantTransfer] SET_COOLDOWN {} = {} ticks for {} (oldServer={})",
                            w.getCooldownGroup(), w.getCooldownTicks(), user.getName(),
                            state.oldServerTalking());
                }
                if (w.getCooldownGroup() != null) {
                    String key = w.getCooldownGroup().toString();
                    if (w.getCooldownTicks() > 0) {
                        state.activeCooldowns().put(key, w.getCooldownGroup());
                        if (state.oldServerTalking()) {
                            state.ghostCooldowns().put(key, w.getCooldownGroup());
                        } else {
                            state.ghostCooldowns().remove(key);
                        }
                    } else {
                        state.activeCooldowns().remove(key);
                        state.ghostCooldowns().remove(key);
                    }
                }

            } else if (type == PacketType.Play.Server.CHANGE_GAME_STATE) {
                // Weather is entirely CHANGE_GAME_STATE-driven; any weather event from the NEW
                // server makes it authoritative, so the ghost snapshot is dropped. But a ramping
                // rain broadcasts RAIN_LEVEL_CHANGE every tick — those still come from the OLD
                // server until the ids message, and must refresh the ghost instead of clearing it
                // (otherwise a switch during a rain ramp leaves rain frozen at a partial level).
                WrapperPlayServerChangeGameState w = new WrapperPlayServerChangeGameState(event);
                WrapperPlayServerChangeGameState.Reason reason = w.getReason();
                boolean weatherEvent = true;
                if (reason == WrapperPlayServerChangeGameState.Reason.BEGIN_RAINING) {
                    state.raining(true);
                } else if (reason == WrapperPlayServerChangeGameState.Reason.END_RAINING) {
                    state.clearWeather();
                } else if (reason == WrapperPlayServerChangeGameState.Reason.RAIN_LEVEL_CHANGE) {
                    state.rainLevel(w.getValue());
                } else if (reason == WrapperPlayServerChangeGameState.Reason.THUNDER_LEVEL_CHANGE) {
                    state.thunderLevel(w.getValue());
                } else {
                    weatherEvent = false;
                }
                if (weatherEvent) {
                    state.ghostWeather(state.oldServerTalking() && state.weatherActive());
                }

            } else if (type == PacketType.Play.Server.OPEN_WINDOW) {
                int windowId = new WrapperPlayServerOpenWindow(event).getContainerId();
                state.openWindowId(windowId);
                // A new-server GUI replaces whatever was open; a late old-server GUI IS the ghost.
                state.ghostWindowId(state.oldServerTalking() ? windowId : -1);

            } else if (type == PacketType.Play.Server.OPEN_HORSE_WINDOW) {
                int windowId = new WrapperPlayServerOpenHorseWindow(event).getWindowId();
                state.openWindowId(windowId);
                state.ghostWindowId(state.oldServerTalking() ? windowId : -1);

            } else if (type == PacketType.Play.Server.CLOSE_WINDOW) {
                state.openWindowId(-1);
                state.ghostWindowId(-1);

            } else if (type == PacketType.Play.Server.PLAYER_ABILITIES) {
                WrapperPlayServerPlayerAbilities w = new WrapperPlayServerPlayerAbilities(event);
                state.rememberAbilities(w.isInGodMode(), w.isFlying(), w.isFlightAllowed(),
                        w.isInCreativeMode(), w.getFlySpeed(), w.getFOVModifier());

            } else if (type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK
                    && state.guardActive() && !state.teleportSeen()) {
                onArrivalTeleport(event, user, state);
                return;
            }

            // Safety net: if the arrival chunk doesn't arrive in time, send a position correction
            // to the known arrival position and keep flight enabled (don't unfreeze brutally).
            if (state.guardActive() && state.teleportSeen()
                    && System.currentTimeMillis() - state.guardArmedAt() > config.guardTimeoutMs()) {
                handleTimeout(user, state);
            }
        } catch (Exception e) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] listener error on {}: {}", type, e.toString());
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null
                || event.getPacketType() != PacketType.Play.Client.CLOSE_WINDOW) {
            return;
        }
        SwitchState state = manager.getOrCreate(user.getUUID());
        if (state.suppressListener()) {
            return;
        }
        // The player closed the GUI themselves (ESC also closes the old server's window mid-switch).
        int windowId = new WrapperPlayClientCloseWindow(event).getWindowId();
        if (windowId == state.openWindowId()) {
            state.openWindowId(-1);
        }
        if (windowId == state.ghostWindowId()) {
            state.ghostWindowId(-1);
        }
    }

    /**
     * A real Respawn reached the client: the patched Velocity's cross-dimension fallback, its
     * opt-in respawn mode ({@code -Dvelocity.seamlessRespawn=true}), or a backend respawning a
     * dead player. The client resets itself, so the seamless machinery stands down accordingly
     * instead of fighting it.
     */
    private void onRespawn(PacketSendEvent event, User user, SwitchState state) {
        WrapperPlayServerRespawn w = new WrapperPlayServerRespawn(event);
        String newDimension = dimensionKey(w.getDimensionTypeRef(),
                state.adapter(() -> ProtocolAdapter.forClientVersion(user.getClientVersion())));
        boolean crossDimension = newDimension != null && state.clientDimension() != null
                && !newDimension.equals(state.clientDimension());
        if (newDimension != null) {
            state.clientDimension(newDimension);
        }
        // Either flavor recreates the LocalPlayer entity: entity-attached client state is gone.
        state.activeEffects().clear();
        state.ghostEffects().clear();
        state.activeCooldowns().clear();
        state.ghostCooldowns().clear();
        state.openWindowId(-1);
        state.ghostWindowId(-1);
        if (!crossDimension) {
            // Same-dimension respawn KEEPS the client level (chunks/entities survive) — a running
            // guard (respawn mode) must keep going: its ghost cleanup is still needed.
            return;
        }
        // Cross-dimension: the client rebuilt its level from scratch; nothing old survives.
        // (Scoreboard tracking is intentionally kept: it is connection-scoped client-side.)
        state.loadedChunks().clear();
        state.trackedEntities().clear();
        state.ghostEntities().clear();
        state.ghostChunks().clear();
        state.clearWeather();
        state.ghostWeather(false);
        if (state.finishGuard()) {
            state.pendingGamemode(-1); // the Respawn itself carries the gamemode
            state.frozeFlight(false);  // never injected: the Respawn precedes the arrival teleport
            coord.sendUnfreeze(user.getUUID());
            // The scoreboard is the one thing that SURVIVES a respawn client-side (it is
            // connection-scoped, not level-scoped), and with the guard finished the release will
            // never clean it: remove the old server's objectives/teams here, before the new
            // server's own scoreboard packets arrive.
            int objectiveCount = state.ghostObjectives().size();
            int teamCount = state.ghostTeams().size();
            for (String name : state.ghostObjectives()) {
                sendSuppressed(user, state, new WrapperPlayServerScoreboardObjective(
                        name, WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, null, null));
            }
            state.activeObjectives().removeAll(state.ghostObjectives());
            state.ghostObjectives().clear();
            for (String name : state.ghostTeams()) {
                sendSuppressed(user, state, new WrapperPlayServerTeams(
                        name, WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty()));
            }
            state.activeTeams().removeAll(state.ghostTeams());
            state.ghostTeams().clear();
            logger.info("[InstantTransfer] cross-dimension switch for {}: Respawn fallback "
                    + "(vanilla world reload), seamless machinery stood down; removed {} ghost "
                    + "objectives, {} ghost teams", user.getName(), objectiveCount, teamCount);
        }
    }

    /** Stable comparison key for a dimension type, whether name- or registry-id-encoded. */
    private static String dimensionKey(DimensionTypeRef ref, ProtocolAdapter pa) {
        if (ref == null) return null;
        String name = null;
        try { ResourceLocation rl = ref.getName(); name = rl == null ? null : rl.toString(); }
        catch (Exception ignored) { }
        Integer id = null;
        try { id = ref.getId(); } catch (Exception e) { }
        return ProtocolAdapter.dimensionKey(name, id, pa.era());
    }

    /**
     * Companion of the PLAYER_INFO_REMOVE filter above: the first ADD_PLAYER of the session (which
     * really creates the client's own tab entry) passes untouched; every later re-add of the own
     * UUID (one per switch) is downgraded to an in-place update of the surviving entry, so the
     * object the player entity cached keeps receiving gamemode/latency/display updates.
     */
    private void onPlayerInfoUpdate(PacketSendEvent event, User user, SwitchState state) {
        WrapperPlayServerPlayerInfoUpdate w = new WrapperPlayServerPlayerInfoUpdate(event);
        if (!w.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER)) {
            return; // plain updates mutate the existing entry: exactly what we want
        }
        WrapperPlayServerPlayerInfoUpdate.PlayerInfo own = null;
        for (WrapperPlayServerPlayerInfoUpdate.PlayerInfo entry : w.getEntries()) {
            if (user.getUUID().equals(entry.getProfileId())) {
                own = entry;
                break;
            }
        }
        if (own == null) {
            return;
        }
        if (!state.ownTabEntryAdded()) {
            state.markOwnTabEntryAdded(); // first login: the add must really create the entry
            return;
        }
        if (w.getEntries().size() == 1) {
            event.setCancelled(true);
        } else {
            List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> others = new ArrayList<>(w.getEntries());
            others.remove(own);
            w.setEntries(others);
            event.markForReEncode(true);
        }
        EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> updateActions = EnumSet.copyOf(w.getActions());
        updateActions.remove(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER);
        if (!updateActions.isEmpty()) {
            sendSuppressed(user, state, new WrapperPlayServerPlayerInfoUpdate(updateActions, own));
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

        // Store arrival position for potential timeout correction (only if absolute coords)
        state.arrivalPosition(w.getX(), w.getY(), w.getZ(), w.getYaw(), w.getPitch(), absolute);
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
            // explicitly (indices/shape resolved per client era by ProtocolAdapter).
            ProtocolAdapter pa = state.adapter(
                    () -> ProtocolAdapter.forClientVersion(user.getClientVersion()));
            List<EntityData<?>> meta = new ArrayList<>();
            if (pa.effectMetadataResetSupported()) {
                EntityData<?> colorOrParticles = pa.effectParticlesAreList()
                        ? new EntityData<>(pa.effectColorIndex(), EntityDataTypes.PARTICLES, List.of())
                        : new EntityData<>(pa.effectColorIndex(), EntityDataTypes.INT, 0);
                meta.add(colorOrParticles);
                meta.add(new EntityData<>(pa.effectAmbientIndex(), EntityDataTypes.BOOLEAN, false));
            }
            // Unsupported eras: REMOVE_ENTITY_EFFECT above already cleared the HUD effect; the swirl
            // metadata lingers until the effect would naturally expire — accepted degradation.
            if (!meta.isEmpty()) {
                sendSuppressed(user, state,
                        new WrapperPlayServerEntityMetadata(state.clientEntityId(), meta));
            }
        }
        ghostEffects.clear();

        // Scoreboard leftovers the new server did not re-create: removing an objective also clears
        // its scores and any display slot (sidebar…) client-side; removing a team clears its
        // memberships, name colors and prefixes.
        Set<String> ghostObjectives = state.ghostObjectives();
        int objectiveCount = ghostObjectives.size();
        for (String name : ghostObjectives) {
            sendSuppressed(user, state, new WrapperPlayServerScoreboardObjective(
                    name, WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE, null, null));
        }
        state.activeObjectives().removeAll(ghostObjectives);
        ghostObjectives.clear();

        Set<String> ghostTeams = state.ghostTeams();
        int teamCount = ghostTeams.size();
        for (String name : ghostTeams) {
            sendSuppressed(user, state, new WrapperPlayServerTeams(
                    name, WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty()));
        }
        state.activeTeams().removeAll(ghostTeams);
        ghostTeams.clear();

        // Item cooldowns never carry over a real join (the new server starts from zero and vanilla
        // does not resend them): zero any overlay left from the previous server, so the same item
        // type in the new inventory doesn't show a stale cooldown sweep.
        Map<String, ResourceLocation> ghostCooldowns = state.ghostCooldowns();
        int cooldownCount = ghostCooldowns.size();
        if (cooldownCount > 0) {
            for (ResourceLocation group : ghostCooldowns.values()) {
                if (config.debug()) {
                    logger.info("[InstantTransfer] zeroing ghost cooldown {} for {}",
                            group, user.getName());
                }
                sendSuppressed(user, state, new WrapperPlayServerSetCooldown(group, 0));
            }
            state.activeCooldowns().keySet().removeAll(ghostCooldowns.keySet());
        }
        ghostCooldowns.clear();

        // Weather from the previous server the new one never countermanded (a non-raining server
        // sends NO weather packet on join): stop it explicitly. END_RAINING alone only starts a
        // slow client-side fade — zeroing both levels makes the sky clear instantly.
        boolean ghostWeather = state.ghostWeather();
        if (ghostWeather) {
            sendSuppressed(user, state, new WrapperPlayServerChangeGameState(
                    WrapperPlayServerChangeGameState.Reason.END_RAINING, 0));
            sendSuppressed(user, state, new WrapperPlayServerChangeGameState(
                    WrapperPlayServerChangeGameState.Reason.RAIN_LEVEL_CHANGE, 0));
            sendSuppressed(user, state, new WrapperPlayServerChangeGameState(
                    WrapperPlayServerChangeGameState.Reason.THUNDER_LEVEL_CHANGE, 0));
            state.clearWeather();
            state.ghostWeather(false);
        }

        // A container GUI left open on the previous server: nothing else ever closes it (no
        // Respawn), and its window id means nothing to the new server — close it client-side.
        int ghostWindow = state.ghostWindowId();
        if (ghostWindow != -1) {
            sendSuppressed(user, state, new WrapperPlayServerCloseWindow(ghostWindow));
            if (state.openWindowId() == ghostWindow) {
                state.openWindowId(-1);
            }
            state.ghostWindowId(-1);
        }

        logger.info("[InstantTransfer] seamless arrival for {} ({}): removed {} ghost entities, "
                        + "{} ghost chunks, {} ghost effects, {} ghost cooldowns, "
                        + "{} ghost objectives, {} ghost teams{}{}",
                user.getName(), reason, entityCount, chunkCount, effectCount, cooldownCount,
                objectiveCount, teamCount,
                ghostWeather ? ", cleared ghost weather" : "",
                ghostWindow != -1 ? ", closed ghost GUI #" + ghostWindow : "");

        // Fire transfer complete event
        UUID uuid = user.getUUID();
        Player player = plugin.getProxy().getPlayer(uuid).orElse(null);
        RegisteredServer previousServer = manager.getPreviousServer(uuid);
        RegisteredServer currentServer = manager.getCurrentServer(uuid);
        if (player != null) {
            plugin.fireTransferComplete(
                    player,
                    previousServer,
                    currentServer,
                    true,
                    reason
            );
        }

        // Notify backend of arrival
        if (previousServer != null) {
            coord.sendArrival(uuid, previousServer.getServerInfo().getName());
        }
    }

    /**
     * Handle guard timeout: the arrival chunk didn't arrive in time.
     * If we have an absolute arrival position, send a position correction and keep flight enabled.
     * If relative (unknown absolute position), just extend the timeout — don't guess the position.
     */
    private void handleTimeout(User user, SwitchState state) {
        if (!state.arrivalPositionKnown()) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] timeout but no arrival position known for {}, extending guard",
                        user.getName());
            }
            // No position known, just extend timeout
            state.markTeleportSeen(System.currentTimeMillis());
            fireTransferTimeout(user);
            return;
        }
        if (!state.arrivalPositionAbsolute()) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] timeout but arrival position was relative for {}, extending guard (no correction)",
                        user.getName());
            }
            // Relative teleport: we don't know the absolute position, don't guess.
            // Just extend the guard timeout and wait for the chunk.
            state.markTeleportSeen(System.currentTimeMillis());
            fireTransferTimeout(user);
            return;
        }
        // Absolute position known: send correction and keep flight enabled.
        if (config.debug()) {
            logger.info("[InstantTransfer] guard timeout for {} — sending position correction to ({},{},{}) and keeping flight",
                    user.getName(), state.arrivalX(), state.arrivalY(), state.arrivalZ());
        }
        sendSuppressed(user, state, new WrapperPlayServerPlayerPositionAndLook(
                state.arrivalX(), state.arrivalY(), state.arrivalZ(),
                state.arrivalYaw(), state.arrivalPitch(),
                (byte) 0, // absolute coordinates (no relative flags)
                0, // teleport ID
                true)); // onGround
        // Restart the guard timer from now — we'll wait longer for the chunk.
        state.markTeleportSeen(System.currentTimeMillis());
        fireTransferTimeout(user);
        // Do NOT call finishGuard() or unfreeze — flight stays on, guard stays active.
        // The normal chunk-arrival path in onPacketSend (CHUNK_DATA handling) will call release().
    }

    private void fireTransferTimeout(User user) {
        UUID uuid = user.getUUID();
        Player player = plugin.getProxy().getPlayer(uuid).orElse(null);
        if (player == null) {
            return;
        }
        plugin.fireTransferTimeout(
                player,
                manager.getPreviousServer(uuid),
                manager.getCurrentServer(uuid)
        );
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
