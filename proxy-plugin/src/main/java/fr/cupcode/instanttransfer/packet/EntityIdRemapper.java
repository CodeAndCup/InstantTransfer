package fr.cupcode.instanttransfer.packet;

import fr.cupcode.instanttransfer.config.PluginConfig;
import fr.cupcode.instanttransfer.protocol.LeadingVarInt;
import fr.cupcode.instanttransfer.switching.SwitchManager;
import fr.cupcode.instanttransfer.switching.SwitchState;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import io.netty.buffer.ByteBuf;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.EnumSet;

/**
 * Entity-id remap for the zero-screen switch (Velocity issue #251).
 *
 * <p>The client never receives a new JoinGame, so it keeps its ORIGINAL player entity id forever,
 * while every backend addresses the player with its own id. Packets targeting the player by the
 * backend id (op permission level for F3+F4, knockback velocity, potion effects, attributes, hurt
 * animation…) would silently miss. The patched Velocity announces both ids over a synthetic
 * {@code instanttransfer:ids} plugin message at each seamless switch (intercepted here, never
 * forwarded); this listener then SWAPS the two ids in both directions. Swapping (rather than a
 * one-way rewrite) also shields the reverse collision — a new-server entity that happens to use the
 * client's own id would otherwise be interpreted by the client as itself.</p>
 *
 * <p>Registered at LOW priority so it runs BEFORE {@code SeamlessPacketListener}: the ghost tracker
 * then only ever sees client-space ids, which is also the space its cleanup packets are sent in.</p>
 */
public final class EntityIdRemapper implements PacketListener {

    public static final String IDS_CHANNEL = "instanttransfer:ids";

    private static final EnumSet<PacketType.Play.Server> SEND_TRACKED = EnumSet.of(
            PacketType.Play.Server.PLUGIN_MESSAGE,
            PacketType.Play.Server.ENTITY_STATUS,
            PacketType.Play.Server.ENTITY_EFFECT, PacketType.Play.Server.REMOVE_ENTITY_EFFECT,
            PacketType.Play.Server.UPDATE_ATTRIBUTES, PacketType.Play.Server.HURT_ANIMATION,
            PacketType.Play.Server.DAMAGE_EVENT, PacketType.Play.Server.ENTITY_ANIMATION,
            PacketType.Play.Server.ENTITY_EQUIPMENT,
            PacketType.Play.Server.SET_PASSENGERS, PacketType.Play.Server.ATTACH_ENTITY,
            PacketType.Play.Server.ENTITY_TELEPORT, PacketType.Play.Server.ENTITY_POSITION_SYNC,
            PacketType.Play.Server.ENTITY_HEAD_LOOK, PacketType.Play.Server.ENTITY_ROTATION,
            PacketType.Play.Server.ENTITY_RELATIVE_MOVE,
            PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION,
            PacketType.Play.Server.DESTROY_ENTITIES, PacketType.Play.Server.CAMERA);

    private static final EnumSet<PacketType.Play.Client> RECEIVE_TRACKED = EnumSet.of(
            PacketType.Play.Client.INTERACT_ENTITY, PacketType.Play.Client.ENTITY_ACTION);

    private final SwitchManager manager;
    private final PluginConfig config;
    private final Logger logger;

    public EntityIdRemapper(SwitchManager manager, PluginConfig config, Logger logger) {
        this.manager = manager;
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
            // A packet injected by SeamlessPacketListener (ghost cleanup, un-freeze, effect
            // removal): it is already built in CLIENT-space ids — swapping it again would
            // retarget it to the backend id and make the client silently drop it.
            return;
        }
        Object t = event.getPacketType();
        if (!(t instanceof PacketType.Play.Server ps) || !SEND_TRACKED.contains(ps)) return;
        PacketTypeCommon type = ps;

        try {
            if (type == PacketType.Play.Server.PLUGIN_MESSAGE) {
                handlePossibleIdsMessage(event, user, state);
                return;
            }
            if (!state.remapActive()) {
                return;
            }

            // ENTITY_STATUS: NOT peek-guarded — its entity id is a fixed big-endian Int (not a
            // VarInt) in every protocol version (packetevents reads it with readInt), so a VarInt
            // peek would misparse the id's high bytes and could wrongly skip a needed remap.
            if (type == PacketType.Play.Server.ENTITY_STATUS) {
                WrapperPlayServerEntityStatus w = new WrapperPlayServerEntityStatus(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
                // Raw rewrite (see RawEntityIdRewriter): PE 2.13.0's motion wrapper misparses on
                // this Paper 1.21.4 network — the in-place VarInt swap restores knockback remap
                // with zero wrapper involvement and a guaranteed-rollback failure path.
                if (RawEntityIdRewriter.rewriteSelfId((ByteBuf) event.getByteBuf(), state)) {
                    // mutated buffer is forwarded as-is; no wrapper owns it
                }

            } else if (type == PacketType.Play.Server.ENTITY_EFFECT) {
                WrapperPlayServerEntityEffect w = new WrapperPlayServerEntityEffect(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
                WrapperPlayServerRemoveEntityEffect w = new WrapperPlayServerRemoveEntityEffect(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
                WrapperPlayServerUpdateAttributes w = new WrapperPlayServerUpdateAttributes(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.HURT_ANIMATION) {
                if (irrelevantByPeek(event, state, movementOffset(event, state))) return;
                WrapperPlayServerHurtAnimation w = new WrapperPlayServerHurtAnimation(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.DAMAGE_EVENT) {
                WrapperPlayServerDamageEvent w = new WrapperPlayServerDamageEvent(event);
                boolean changed = rewriteBackendToClient(w.getEntityId(), w::setEntityId, state);
                // Source cause/direct are encoded as (entity id + 1); 0 means "none".
                if (w.getSourceCauseId() > 0) {
                    changed |= rewriteBackendToClient(w.getSourceCauseId() - 1,
                            id -> w.setSourceCauseId(id + 1), state);
                }
                if (w.getSourceDirectId() > 0) {
                    changed |= rewriteBackendToClient(w.getSourceDirectId() - 1,
                            id -> w.setSourceDirectId(id + 1), state);
                }
                if (changed) {
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.ENTITY_ANIMATION) {
                if (irrelevantByPeek(event, state, movementOffset(event, state))) return;
                WrapperPlayServerEntityAnimation w = new WrapperPlayServerEntityAnimation(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
                // Raw rewrite: PE 2.13.0's V_1_21_4 metadata registry is incomplete (type id 32
                // unknown) — wrapper parse throws mid-body and any re-encode corrupts the frame.
                // The raw path swaps ONLY the leading entity-id VarInt, payload untouched.
                RawEntityIdRewriter.rewriteSelfId((ByteBuf) event.getByteBuf(), state);

            } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                if (irrelevantByPeek(event, state, movementOffset(event, state))) return;
                WrapperPlayServerEntityEquipment w = new WrapperPlayServerEntityEquipment(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.SET_PASSENGERS) {
                WrapperPlayServerSetPassengers w = new WrapperPlayServerSetPassengers(event);
                boolean changed = rewriteBackendToClient(w.getEntityId(), w::setEntityId, state);
                int[] passengers = w.getPassengers();
                for (int i = 0; i < passengers.length; i++) {
                    if (passengers[i] == state.backendEntityId()) {
                        passengers[i] = state.clientEntityId();
                        changed = true;
                    }
                }
                if (changed) {
                    w.setPassengers(passengers);
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.ATTACH_ENTITY) {
                WrapperPlayServerAttachEntity w = new WrapperPlayServerAttachEntity(event);
                boolean changed = rewriteBackendToClient(w.getAttachedId(), w::setAttachedId, state);
                changed |= rewriteBackendToClient(w.getHoldingId(), w::setHoldingId, state);
                if (changed) {
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
                // Raw rewrite: same PE-skew family as velocity/metadata on 1.21.4.
                RawEntityIdRewriter.rewriteSelfId((ByteBuf) event.getByteBuf(), state);

            } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                // Raw rewrite: this is the type whose truncated frame crashed the client
                // (3-byte entity_position_sync) — wrapper path is banned on this network.
                RawEntityIdRewriter.rewriteSelfId((ByteBuf) event.getByteBuf(), state);

            } else if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                if (irrelevantByPeek(event, state, movementOffset(event, state))) return;
                WrapperPlayServerEntityHeadLook w = new WrapperPlayServerEntityHeadLook(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_ROTATION) {
                if (irrelevantByPeek(event, state, movementOffset(event, state))) return;
                WrapperPlayServerEntityRotation w = new WrapperPlayServerEntityRotation(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                if (irrelevantByPeek(event, state, movementOffset(event, state))) return;
                WrapperPlayServerEntityRelativeMove w = new WrapperPlayServerEntityRelativeMove(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                if (irrelevantByPeek(event, state, movementOffset(event, state))) return;
                WrapperPlayServerEntityRelativeMoveAndRotation w =
                        new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                // Do NOT rewrite SPAWN_ENTITY - backend assigns new entity IDs for mobs/items/etc.
                // The player entity is never spawned via SPAWN_ENTITY (JoinGame is suppressed).
                // Rewriting would incorrectly change mob/item entity IDs if they collide with backendEntityId.
            } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
                WrapperPlayServerDestroyEntities w = new WrapperPlayServerDestroyEntities(event);
                int[] ids = w.getEntityIds();
                boolean changed = false;
                for (int i = 0; i < ids.length; i++) {
                    if (ids[i] == state.backendEntityId()) {
                        ids[i] = state.clientEntityId();
                        changed = true;
                    }
                }
                if (changed) {
                    w.setEntityIds(ids);
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.CAMERA) {
                WrapperPlayServerCamera w = new WrapperPlayServerCamera(event);
                remapSend(event, w.getCameraId(), w::setCameraId, state);
            }
        } catch (Exception e) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] remap error on {}: {}", type, e.toString());
            }
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        SwitchState state = manager.getOrCreate(user.getUUID());
        if (!state.remapActive()) {
            return;
        }
        Object t = event.getPacketType();
        if (!(t instanceof PacketType.Play.Client pc) || !RECEIVE_TRACKED.contains(pc)) return;
        PacketTypeCommon type = pc;

        try {
            if (type == PacketType.Play.Client.INTERACT_ENTITY) {
                // Client-built bodies carry no packet-id prefix: the leading id sits at offset 0.
                if (irrelevantByPeek(event, state, 0)) return;
                WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
                remapReceive(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
                if (irrelevantByPeek(event, state, 0)) return;
                WrapperPlayClientEntityAction w = new WrapperPlayClientEntityAction(event);
                remapReceive(event, w.getEntityId(), w::setEntityId, state);
            }
        } catch (Exception e) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] remap error on {}: {}", type, e.toString());
            }
        }
    }

    /** The patched Velocity announces "clientId:backendId" on our channel at each seamless switch. */
    private void handlePossibleIdsMessage(PacketSendEvent event, User user, SwitchState state) {
        WrapperPlayServerPluginMessage w = new WrapperPlayServerPluginMessage(event);
        if (!IDS_CHANNEL.equals(w.getChannelName())) {
            return;
        }
        event.setCancelled(true); // synthetic proxy->plugin signal, never meant for the client
        // The patch writes this message BEFORE relaying anything from the new backend: from here
        // on, incoming traffic is the new server's (late old-server packets can no longer occur).
        state.markNewServerTalking();
        purgeStaleScoreboard(user, state);
        purgeStaleEntities(user, state);
        String payload = new String(w.getData(), StandardCharsets.UTF_8);
        if (!verifyIdsHmac(payload, state)) {
            return;
        }
        // When a secret is configured the last segment is the HMAC: parse the signing payload.
        // NOTE: the Velocity patch (velocity-patch/seamless.patch) currently sends ids unsigned,
        // so ids-hmac-secret must stay empty until the patch signs its payload.
        String signingPayload = payload;
        String idsSecret = config.idsHmacSecret();
        if (idsSecret != null && !idsSecret.isEmpty()) {
            int lastColon = payload.lastIndexOf(':');
            if (lastColon < 1) {
                return;
            }
            signingPayload = payload.substring(0, lastColon);
        }
        String[] parts = signingPayload.split(":");
        if (parts.length < 2) {
            logger.warn("[InstantTransfer] bad ids payload: {}", payload);
            return;
        }
        int clientId;
        int backendId;
        try {
            clientId = Integer.parseInt(parts[0]);
            backendId = Integer.parseInt(parts[1]);
            state.entityIds(clientId, backendId);
            if (parts.length >= 3) {
                // The new backend's gamemode: applied on arrival (release), since the client no longer
                // learns it from JoinGame/Respawn.
                state.pendingGamemode(Integer.parseInt(parts[2]));
            }
        } catch (NumberFormatException e) {
            logger.warn("[InstantTransfer] bad ids payload: {}", payload);
            return;
        }
        logger.info("[InstantTransfer] entity-id remap for {}: client={} backend={} gamemode={} ({})",
                user.getName(), clientId, backendId,
                parts.length >= 3 ? parts[2] : "?",
                clientId == backendId ? "identical, remap idle" : "swap active");
    }

    /** Verify HMAC-SHA256 signature on the instanttransfer:ids payload.
     *  Expected format: clientId:backendId[:gamemode]:hmacHex
     *  The hmacHex is HMAC-SHA256 of the prefix (clientId:backendId[:gamemode]) using the
     *  shared secret configured in PluginConfig.idsHmacSecret.
     *  Returns true if the HMAC matches, or if no secret is configured (backward compatibility). */
    private boolean verifyIdsHmac(String payload, SwitchState state) {
        if (config.idsHmacSecret() == null || config.idsHmacSecret().isEmpty()) {
            return true; // no secret configured → allow for backward compatibility
        }
        int lastColon = payload.lastIndexOf(':');
        if (lastColon < 1) {
            return false;
        }
        String signingPayload = payload.substring(0, lastColon);
        String receivedHmac = payload.substring(lastColon + 1);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    config.idsHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] computed = mac.doFinal(signingPayload.getBytes(StandardCharsets.UTF_8));
            String computedHex = bytesToHex(computed);
            return MessageDigest.isEqual(computedHex.getBytes(StandardCharsets.UTF_8), receivedHmac.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] HMAC verification error: {}", e.toString());
            }
            return false;
        }
    }

    /** Convert byte array to uppercase hex string. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * The scoreboard is connection-scoped client-side: whatever the previous server displayed
     * survives a zero-screen switch until someone removes it. A destination WITHOUT its own
     * scoreboard would otherwise show the previous server's sidebar indefinitely (nothing
     * re-creates the name, so the duplicate-CREATE guard never fires). Removed the instant the
     * ids message proves the old server is done talking — long before the arrival guard
     * completes, because sidebar cleanup has no dependency on world chunks. Idempotent: an
     * extra REMOVE for a name the client already dropped is a vanilla no-op.
     */
    private void purgeStaleScoreboard(User user, SwitchState state) {
        if (state.activeObjectives().isEmpty() && state.activeTeams().isEmpty()) {
            return;
        }
        state.suppressListener(true);
        try {
            for (String name : state.activeObjectives()) {
                user.sendPacket(new WrapperPlayServerScoreboardObjective(
                        name, WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
                        null, null));
            }
            for (String team : state.activeTeams()) {
                user.sendPacket(new WrapperPlayServerTeams(
                        team, WrapperPlayServerTeams.TeamMode.REMOVE, Optional.empty()));
            }
            if (config.debug()) {
                logger.info("[InstantTransfer] purged {} objective(s) / {} team(s) at switch",
                        state.activeObjectives().size(), state.activeTeams().size());
            }
        } catch (Exception e) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] scoreboard purge failed: {}", e.toString());
            }
        } finally {
            state.suppressListener(false);
        }
    }

    /**
     * Ghost entities from the previous server are destroyed the instant the ids message proves
     * it is done talking — NOT at release(), whose cleanup leg demonstrably never reaches the
     * client on this network (see ledger). DestroyEntities is world-independent and the ids are
     * already client-space (the remapper only ever swaps the PLAYER's id; other entities keep
     * the ids the old server itself sent), so this is safe to fire before any chunk arrives.
     */
    private void purgeStaleEntities(User user, SwitchState state) {
        if (state.ghostEntities().isEmpty()) {
            return;
        }
        int[] ids = new int[state.ghostEntities().size()];
        int i = 0;
        for (Integer gid : state.ghostEntities()) {
            ids[i++] = gid;
        }
        state.suppressListener(true);
        try {
            user.sendPacket(new WrapperPlayServerDestroyEntities(ids));
            if (config.debug()) {
                logger.info("[InstantTransfer] purged {} ghost entit(y/ies) at switch", ids.length);
            }
        } catch (Exception e) {
            if (config.debug()) {
                logger.warn("[InstantTransfer] ghost-entity purge failed: {}", e.toString());
            }
        } finally {
            state.suppressListener(false);
        }
        state.ghostEntities().clear();
    }

    private interface IdSetter {
        void set(int id);
    }

    /** Rewrite backendEntityId -> clientEntityId (server->client direction only). */
    private boolean rewriteBackendToClient(int id, IdSetter setter, SwitchState state) {
        if (id == state.backendEntityId()) {
            setter.set(state.clientEntityId());
            return true;
        }
        return false;
    }

    /** Rewrite clientEntityId -> backendEntityId (client->server direction only). */
    private boolean rewriteClientToBackend(int id, IdSetter setter, SwitchState state) {
        if (id == state.clientEntityId()) {
            setter.set(state.backendEntityId());
            return true;
        }
        return false;
    }

    private void remapSend(com.github.retrooper.packetevents.event.PacketSendEvent event,
                           int id, IdSetter setter, SwitchState state) {
        if (rewriteBackendToClient(id, setter, state)) {
            event.markForReEncode(true);
        }
    }

    private void remapReceive(com.github.retrooper.packetevents.event.PacketReceiveEvent event,
                              int id, IdSetter setter, SwitchState state) {
        if (rewriteClientToBackend(id, setter, state)) {
            event.markForReEncode(true);
        }
    }

    /**
     * Fast-path: most movement packets target OTHER entities; if the leading id is neither of
     * ours we can skip wrapper construction + potential re-encode entirely. The sentinel never
     * causes a skip ({@code id != SENTINEL} clause): an unreadable id always takes the wrapper
     * path, which is merely slower, never wrong.
     */
    private static boolean irrelevantByPeek(PacketSendEvent event, SwitchState state, int offset) {
        int id = LeadingVarInt.peek((ByteBuf) event.getByteBuf(), offset); // typed Object by PE 2.x
        return id != LeadingVarInt.SENTINEL
                && id != state.backendEntityId()
                && id != state.clientEntityId();
    }

    /** Same fast-path for client->server packets (fixed offset 0, see call sites). */
    private static boolean irrelevantByPeek(PacketReceiveEvent event, SwitchState state, int offset) {
        int id = LeadingVarInt.peek((ByteBuf) event.getByteBuf(), offset); // typed Object by PE 2.x
        return id != LeadingVarInt.SENTINEL
                && id != state.backendEntityId()
                && id != state.clientEntityId();
    }

    /**
     * 0 once proven; -1 means "peek unusable for this user — always take the wrapper path".
     * Probed on the first movement packet: peek at offset 0 must match the wrapper's leading
     * id exactly once; only then is the cache locked in.
     */
    private int movementOffset(PacketSendEvent event, SwitchState state) {
        Integer cached = state.movementHeaderOffsetCache();
        if (cached != null) {
            if (cached >= 0 && config.debug() && state.nextMovementPeekCheck() < 10) {
                verifyMovementPeek(event, state); // one-time live sanity vs wrapper decode
            }
            return cached;
        }
        WrapperPlayServerEntityHeadLook probe =
                new WrapperPlayServerEntityHeadLook(event); // smallest read of the family (id + 1 byte)
        int wrapperId = probe.getEntityId();
        int peeked = LeadingVarInt.peek((ByteBuf) event.getByteBuf(), 0);
        int resolved = (peeked == wrapperId) ? 0 : LeadingVarInt.SENTINEL;
        state.movementHeaderOffsetCache(resolved);
        return resolved;
    }

    /**
     * One-time live sanity check (debug only, first ten movement packets): the cached-offset peek
     * must match what a fresh wrapper decodes. Mirrors SeamlessPacketListener#verifyChunkPeek.
     * PENDING LIVE CONFIRMATION (no network in CI): run with debug on a real proxy and check for
     * MISMATCH lines before trusting the fast path in production.
     */
    private void verifyMovementPeek(PacketSendEvent event, SwitchState state) {
        try {
            ByteBuf buf = (ByteBuf) event.getByteBuf(); // typed Object by PE 2.x; Netty buf here
            Integer offset = state.movementHeaderOffsetCache();
            int peeked = LeadingVarInt.peek(buf, offset != null ? offset : 0);
            int wrapperId = new WrapperPlayServerEntityHeadLook(event).getEntityId();
            logger.info("[InstantTransfer] movement peek verify for {}: peeked {} vs wrapper {}{}",
                    event.getUser() != null ? event.getUser().getName() : "?",
                    peeked, wrapperId, peeked == wrapperId ? "" : "  *** MISMATCH — peek disabled ***");
        } catch (Exception e) {
            logger.warn("[InstantTransfer] movement peek verify failed: {}", e.toString());
        }
    }
}
