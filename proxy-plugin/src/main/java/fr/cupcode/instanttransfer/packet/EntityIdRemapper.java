package fr.cupcode.instanttransfer.packet;

import fr.cupcode.instanttransfer.config.PluginConfig;
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;

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
        PacketTypeCommon type = event.getPacketType();

        try {
            if (type == PacketType.Play.Server.PLUGIN_MESSAGE) {
                handlePossibleIdsMessage(event, user, state);
                return;
            }
            if (!state.remapActive()) {
                return;
            }

            if (type == PacketType.Play.Server.ENTITY_STATUS) {
                WrapperPlayServerEntityStatus w = new WrapperPlayServerEntityStatus(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
                WrapperPlayServerEntityVelocity w = new WrapperPlayServerEntityVelocity(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

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
                WrapperPlayServerEntityAnimation w = new WrapperPlayServerEntityAnimation(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
                WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
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
                WrapperPlayServerEntityTeleport w = new WrapperPlayServerEntityTeleport(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                WrapperPlayServerEntityPositionSync w = new WrapperPlayServerEntityPositionSync(event);
                remapSend(event, w.getId(), w::setId, state);

            } else if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                WrapperPlayServerEntityHeadLook w = new WrapperPlayServerEntityHeadLook(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_ROTATION) {
                WrapperPlayServerEntityRotation w = new WrapperPlayServerEntityRotation(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                WrapperPlayServerEntityRelativeMove w = new WrapperPlayServerEntityRelativeMove(event);
                remapSend(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
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
        PacketTypeCommon type = event.getPacketType();

        try {
            if (type == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(event);
                remapReceive(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
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
        String payload = new String(w.getData(), StandardCharsets.UTF_8);
        String[] parts = payload.split(":");
        if (parts.length < 2) {
            logger.warn("[InstantTransfer] bad ids payload: {}", payload);
            return;
        }
        int clientId = Integer.parseInt(parts[0]);
        int backendId = Integer.parseInt(parts[1]);
        state.entityIds(clientId, backendId);
        if (parts.length >= 3) {
            // The new backend's gamemode: applied on arrival (release), since the client no longer
            // learns it from JoinGame/Respawn.
            state.pendingGamemode(Integer.parseInt(parts[2]));
        }
        logger.info("[InstantTransfer] entity-id remap for {}: client={} backend={} gamemode={} ({})",
                user.getName(), clientId, backendId,
                parts.length >= 3 ? parts[2] : "?",
                clientId == backendId ? "identical, remap idle" : "swap active");
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
}
