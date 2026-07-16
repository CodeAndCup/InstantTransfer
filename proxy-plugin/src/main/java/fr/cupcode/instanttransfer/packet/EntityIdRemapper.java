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
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
                WrapperPlayServerEntityVelocity w = new WrapperPlayServerEntityVelocity(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_EFFECT) {
                WrapperPlayServerEntityEffect w = new WrapperPlayServerEntityEffect(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
                WrapperPlayServerRemoveEntityEffect w = new WrapperPlayServerRemoveEntityEffect(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
                WrapperPlayServerUpdateAttributes w = new WrapperPlayServerUpdateAttributes(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.HURT_ANIMATION) {
                WrapperPlayServerHurtAnimation w = new WrapperPlayServerHurtAnimation(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.DAMAGE_EVENT) {
                WrapperPlayServerDamageEvent w = new WrapperPlayServerDamageEvent(event);
                boolean changed = swapInto(w.getEntityId(), w::setEntityId, state);
                // Source cause/direct are encoded as (entity id + 1); 0 means "none".
                if (w.getSourceCauseId() > 0) {
                    changed |= swapInto(w.getSourceCauseId() - 1,
                            id -> w.setSourceCauseId(id + 1), state);
                }
                if (w.getSourceDirectId() > 0) {
                    changed |= swapInto(w.getSourceDirectId() - 1,
                            id -> w.setSourceDirectId(id + 1), state);
                }
                if (changed) {
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.ENTITY_ANIMATION) {
                WrapperPlayServerEntityAnimation w = new WrapperPlayServerEntityAnimation(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_METADATA) {
                WrapperPlayServerEntityMetadata w = new WrapperPlayServerEntityMetadata(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                WrapperPlayServerEntityEquipment w = new WrapperPlayServerEntityEquipment(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.SET_PASSENGERS) {
                WrapperPlayServerSetPassengers w = new WrapperPlayServerSetPassengers(event);
                boolean changed = swapInto(w.getEntityId(), w::setEntityId, state);
                int[] passengers = w.getPassengers();
                boolean arrayChanged = false;
                for (int i = 0; i < passengers.length; i++) {
                    int swapped = state.swapEntityId(passengers[i]);
                    if (swapped != passengers[i]) {
                        passengers[i] = swapped;
                        arrayChanged = true;
                    }
                }
                if (arrayChanged) {
                    w.setPassengers(passengers);
                }
                if (changed || arrayChanged) {
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.ATTACH_ENTITY) {
                WrapperPlayServerAttachEntity w = new WrapperPlayServerAttachEntity(event);
                boolean changed = swapInto(w.getAttachedId(), w::setAttachedId, state);
                changed |= swapInto(w.getHoldingId(), w::setHoldingId, state);
                if (changed) {
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
                WrapperPlayServerEntityTeleport w = new WrapperPlayServerEntityTeleport(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
                WrapperPlayServerEntityPositionSync w = new WrapperPlayServerEntityPositionSync(event);
                remap(event, w.getId(), w::setId, state);

            } else if (type == PacketType.Play.Server.ENTITY_HEAD_LOOK) {
                WrapperPlayServerEntityHeadLook w = new WrapperPlayServerEntityHeadLook(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_ROTATION) {
                WrapperPlayServerEntityRotation w = new WrapperPlayServerEntityRotation(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE) {
                WrapperPlayServerEntityRelativeMove w = new WrapperPlayServerEntityRelativeMove(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.ENTITY_RELATIVE_MOVE_AND_ROTATION) {
                WrapperPlayServerEntityRelativeMoveAndRotation w =
                        new WrapperPlayServerEntityRelativeMoveAndRotation(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
                WrapperPlayServerSpawnEntity w = new WrapperPlayServerSpawnEntity(event);
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
                WrapperPlayServerDestroyEntities w = new WrapperPlayServerDestroyEntities(event);
                int[] ids = w.getEntityIds();
                boolean changed = false;
                for (int i = 0; i < ids.length; i++) {
                    int swapped = state.swapEntityId(ids[i]);
                    if (swapped != ids[i]) {
                        ids[i] = swapped;
                        changed = true;
                    }
                }
                if (changed) {
                    w.setEntityIds(ids);
                    event.markForReEncode(true);
                }

            } else if (type == PacketType.Play.Server.CAMERA) {
                WrapperPlayServerCamera w = new WrapperPlayServerCamera(event);
                remap(event, w.getCameraId(), w::setCameraId, state);
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
                remap(event, w.getEntityId(), w::setEntityId, state);

            } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
                WrapperPlayClientEntityAction w = new WrapperPlayClientEntityAction(event);
                remap(event, w.getEntityId(), w::setEntityId, state);
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

    /** Swap the id if it belongs to the client/backend pair; returns whether a change was made. */
    private boolean swapInto(int id, IdSetter setter, SwitchState state) {
        int swapped = state.swapEntityId(id);
        if (swapped != id) {
            setter.set(swapped);
            return true;
        }
        return false;
    }

    private void remap(com.github.retrooper.packetevents.event.ProtocolPacketEvent event,
                       int id, IdSetter setter, SwitchState state) {
        if (swapInto(id, setter, state)) {
            event.markForReEncode(true);
        }
    }
}
