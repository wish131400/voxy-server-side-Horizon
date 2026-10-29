package dev.xantha.vss.networking;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.BandwidthUpdateC2SPayload;
import dev.xantha.vss.networking.payloads.BatchChunkRequestC2SPayload;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.CancelRequestC2SPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeC2SPayload;
import dev.xantha.vss.networking.payloads.HandshakeRequestS2CPayload;
import dev.xantha.vss.networking.payloads.RegionPresenceC2SPayload;
import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsC2SPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import dev.xantha.vss.networking.payloads.StructureTemplateRequestC2SPayload;
import dev.xantha.vss.networking.payloads.StructureTemplateFragmentS2CPayload;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import dev.xantha.vss.compat.BandwidthOptimizerCompat;
import io.netty.channel.Channel;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import dev.xantha.vss.platform.ConnectionChannelAccess;
import java.util.function.Consumer;

import dev.xantha.vss.networking.server.ServerIdentityConfigurationTask;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class VSSNetworking {
    private static final String CLIENT_PACKET_HANDLERS_CLASS = "dev.xantha.vss.networking.client.VSSClientPacketHandlers";

    private VSSNetworking() {
    }

    private static Consumer<CustomPacketPayload> clientSender;

    public static void register() {
        PayloadTypeRegistry.clientboundConfiguration().register(ServerIdentityS2CPayload.TYPE, ServerIdentityS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HandshakeC2SPayload.TYPE, (payload, context) ->
                VSSServerNetworking.handleHandshake(payload, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(BatchChunkRequestC2SPayload.TYPE, BatchChunkRequestC2SPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BatchChunkRequestC2SPayload.TYPE, (payload, context) ->
                VSSServerNetworking.handleBatchRequest(payload, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(CancelRequestC2SPayload.TYPE, CancelRequestC2SPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CancelRequestC2SPayload.TYPE, (payload, context) ->
                VSSServerNetworking.handleCancel(payload, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(BandwidthUpdateC2SPayload.TYPE, BandwidthUpdateC2SPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BandwidthUpdateC2SPayload.TYPE, (payload, context) ->
                VSSServerNetworking.handleBandwidthUpdate(payload, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(RegionPresenceC2SPayload.TYPE, RegionPresenceC2SPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RegionPresenceC2SPayload.TYPE, (payload, context) ->
                VSSServerNetworking.handleRegionPresence(payload, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(LostCityHintsC2SPayload.TYPE, LostCityHintsC2SPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(LostCityHintsC2SPayload.TYPE, (payload, context) ->
                VSSServerNetworking.handleLostCityHints(payload, context.player()));
        PayloadTypeRegistry.serverboundPlay().register(StructureTemplateRequestC2SPayload.TYPE, StructureTemplateRequestC2SPayload.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StructureTemplateRequestC2SPayload.TYPE, (payload, context) ->
                VSSServerNetworking.handleStructureTemplateRequest(payload, context.player()));
        PayloadTypeRegistry.clientboundPlay().register(SessionConfigS2CPayload.TYPE, SessionConfigS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BatchResponseS2CPayload.TYPE, BatchResponseS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DirtyColumnsS2CPayload.TYPE, DirtyColumnsS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(VoxelColumnS2CPayload.TYPE, VoxelColumnS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FarPlayersS2CPayload.TYPE, FarPlayersS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HandshakeRequestS2CPayload.TYPE, HandshakeRequestS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldgenProfileS2CPayload.TYPE, WorldgenProfileS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(WorldgenProfileFragmentS2CPayload.TYPE, WorldgenProfileFragmentS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(LostCityHintsS2CPayload.TYPE, LostCityHintsS2CPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StructureTemplateFragmentS2CPayload.TYPE, StructureTemplateFragmentS2CPayload.STREAM_CODEC);
        // Configuration packets arrive before play and before Voxy chooses its cache identity.
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (ServerConfigurationNetworking.canSend(handler, ServerIdentityS2CPayload.TYPE)) {
                ServerIdentityConfigurationTask.send(handler);
            }
        });
    }

    public static void bindClientSender(Consumer<CustomPacketPayload> sender) {
        clientSender = java.util.Objects.requireNonNull(sender);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        Consumer<CustomPacketPayload> sender = clientSender;
        if (sender == null) throw new IllegalStateException("VSS client networking has not initialized");
        sender.accept(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (trySendToIntegratedHost(player, payload)) {
            return;
        }
        if (payload instanceof WorldgenProfileS2CPayload profile) {
            WorldgenProfileTransfer.send(profile, part -> ServerPlayNetworking.send(player, part));
        } else {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static boolean canSendQueuedToPlayer(ServerPlayer player) {
        Channel channel = ((ConnectionChannelAccess) ((dev.xantha.vss.platform.ServerConnectionAccess) player.connection).vss$connection()).vss$channel();
        return channel != null && channel.isActive() && channel.isWritable()
                && !BandwidthOptimizerCompat.isWaitingForClient(channel);
    }

    public static void sendQueuedToPlayer(ServerPlayer player, CustomPacketPayload payload, Runnable encoded) {
        try {
            sendToPlayer(player, payload);
            // Connection schedules encoding on this event loop. Release our
            // reservation only after that work, without changing BO's batching.
            ((ConnectionChannelAccess) ((dev.xantha.vss.platform.ServerConnectionAccess) player.connection).vss$connection()).vss$channel().eventLoop().execute(encoded);
        } catch (RuntimeException | Error e) {
            encoded.run();
            throw e;
        }
    }

    private static boolean trySendToIntegratedHost(ServerPlayer player, CustomPacketPayload payload) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return false;
        }
        Object handled = invokeClientHandler(
                "tryHandleIntegratedHostPayload",
                new Class<?>[] {ServerPlayer.class, CustomPacketPayload.class},
                player,
                payload);
        return Boolean.TRUE.equals(handled);
    }

    private static Object invokeClientHandler(String methodName, Class<?>[] parameterTypes, Object... args) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return null;
        }
        try {
            Class<?> handlersClass = Class.forName(CLIENT_PACKET_HANDLERS_CLASS);
            Method method = dev.xantha.vss.common.ReflectionAccess.method(handlersClass, methodName, parameterTypes);
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("VSS client packet handler failed: " + methodName, cause);
        } catch (ReflectiveOperationException e) {
            VSSLogger.error("Unable to invoke VSS client packet handler: " + methodName, e);
            return null;
        }
    }
}