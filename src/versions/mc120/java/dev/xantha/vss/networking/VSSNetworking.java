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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import dev.xantha.vss.networking.payloads.LegacyStreamCodec;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import java.util.Map;
import java.util.HashMap;


import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import dev.xantha.vss.platform.ConnectionChannelAccess;
import java.util.function.Consumer;


import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import dev.xantha.vss.networking.payloads.LegacyPayload;
import net.minecraft.server.level.ServerPlayer;

public final class VSSNetworking {
    private static final String CLIENT_PACKET_HANDLERS_CLASS = "dev.xantha.vss.networking.client.VSSClientPacketHandlers";

    private VSSNetworking() {
    }

    private static Consumer<LegacyPayload> clientSender;

    private static final Map<ResourceLocation, LegacyStreamCodec<FriendlyByteBuf, ?>> CODECS = new HashMap<>();
    private static <T extends LegacyPayload> void codec(LegacyPayload.Type<T> type, LegacyStreamCodec<FriendlyByteBuf, T> codec) { CODECS.put(type.id(), codec); }
    private static <T extends LegacyPayload> void receive(LegacyPayload.Type<T> type, LegacyStreamCodec<FriendlyByteBuf, T> codec, java.util.function.BiConsumer<T, ServerPlayer> handler) {
        ServerPlayNetworking.registerGlobalReceiver(type.id(), (server, player, listener, buffer, response) -> {
            T payload = codec.decode(buffer);
            server.execute(() -> handler.accept(payload, player));
        });
    }
    public static FriendlyByteBuf encode(LegacyPayload payload) {
        var buffer = PacketByteBufs.create();
        @SuppressWarnings("unchecked") var codec = (LegacyStreamCodec<FriendlyByteBuf, LegacyPayload>) CODECS.get(payload.type().id());
        if (codec == null) throw new IllegalArgumentException("Unregistered VSS payload " + payload.type().id());
        codec.encode(buffer, payload);
        return buffer;
    }
    public static void register() {
        codec(ServerIdentityS2CPayload.TYPE, ServerIdentityS2CPayload.STREAM_CODEC);
        codec(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.STREAM_CODEC);
        codec(BatchChunkRequestC2SPayload.TYPE, BatchChunkRequestC2SPayload.STREAM_CODEC);
        codec(CancelRequestC2SPayload.TYPE, CancelRequestC2SPayload.STREAM_CODEC);
        codec(BandwidthUpdateC2SPayload.TYPE, BandwidthUpdateC2SPayload.STREAM_CODEC);
        codec(RegionPresenceC2SPayload.TYPE, RegionPresenceC2SPayload.STREAM_CODEC);
        codec(LostCityHintsC2SPayload.TYPE, LostCityHintsC2SPayload.STREAM_CODEC);
        codec(StructureTemplateRequestC2SPayload.TYPE, StructureTemplateRequestC2SPayload.STREAM_CODEC);
        codec(SessionConfigS2CPayload.TYPE, SessionConfigS2CPayload.STREAM_CODEC);
        codec(BatchResponseS2CPayload.TYPE, BatchResponseS2CPayload.STREAM_CODEC);
        codec(DirtyColumnsS2CPayload.TYPE, DirtyColumnsS2CPayload.STREAM_CODEC);
        codec(VoxelColumnS2CPayload.TYPE, VoxelColumnS2CPayload.STREAM_CODEC);
        codec(FarPlayersS2CPayload.TYPE, FarPlayersS2CPayload.STREAM_CODEC);
        codec(HandshakeRequestS2CPayload.TYPE, HandshakeRequestS2CPayload.STREAM_CODEC);
        codec(WorldgenProfileS2CPayload.TYPE, WorldgenProfileS2CPayload.STREAM_CODEC);
        codec(WorldgenProfileFragmentS2CPayload.TYPE, WorldgenProfileFragmentS2CPayload.STREAM_CODEC);
        codec(LostCityHintsS2CPayload.TYPE, LostCityHintsS2CPayload.STREAM_CODEC);
        codec(StructureTemplateFragmentS2CPayload.TYPE, StructureTemplateFragmentS2CPayload.STREAM_CODEC);
        receive(HandshakeC2SPayload.TYPE, HandshakeC2SPayload.STREAM_CODEC, VSSServerNetworking::handleHandshake);
        receive(BatchChunkRequestC2SPayload.TYPE, BatchChunkRequestC2SPayload.STREAM_CODEC, VSSServerNetworking::handleBatchRequest);
        receive(CancelRequestC2SPayload.TYPE, CancelRequestC2SPayload.STREAM_CODEC, VSSServerNetworking::handleCancel);
        receive(BandwidthUpdateC2SPayload.TYPE, BandwidthUpdateC2SPayload.STREAM_CODEC, VSSServerNetworking::handleBandwidthUpdate);
        receive(RegionPresenceC2SPayload.TYPE, RegionPresenceC2SPayload.STREAM_CODEC, VSSServerNetworking::handleRegionPresence);
        receive(LostCityHintsC2SPayload.TYPE, LostCityHintsC2SPayload.STREAM_CODEC, VSSServerNetworking::handleLostCityHints);
        receive(StructureTemplateRequestC2SPayload.TYPE, StructureTemplateRequestC2SPayload.STREAM_CODEC, VSSServerNetworking::handleStructureTemplateRequest);
        net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            sender.sendPacket(ServerIdentityS2CPayload.TYPE.id(), encode(ServerIdentityS2CPayload.fromConfig(
                    dev.xantha.vss.api.column.ColumnFilters.snapshot().cacheKey())));
        });
        net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking.registerGlobalReceiver(ServerIdentityS2CPayload.TYPE.id(),
                (server, handler, understood, buffer, synchronizer, response) -> { });
    }

    public static void bindClientSender(Consumer<LegacyPayload> sender) {
        clientSender = java.util.Objects.requireNonNull(sender);
    }

    public static void sendToServer(LegacyPayload payload) {
        Consumer<LegacyPayload> sender = clientSender;
        if (sender == null) throw new IllegalStateException("VSS client networking has not initialized");
        sender.accept(payload);
    }

    public static void sendToPlayer(ServerPlayer player, LegacyPayload payload) {
        if (trySendToIntegratedHost(player, payload)) {
            return;
        }
        if (payload instanceof WorldgenProfileS2CPayload profile) {
            WorldgenProfileTransfer.send(profile, part -> sendEncoded(player, part));
        } else {
            sendEncoded(player, payload);
        }
    }

    private static void sendEncoded(ServerPlayer player, LegacyPayload payload) {
        ServerPlayNetworking.send(player, payload.type().id(), encode(payload));
    }

    public static boolean canSendQueuedToPlayer(ServerPlayer player) {
        Channel channel = ((ConnectionChannelAccess) ((dev.xantha.vss.platform.ServerConnectionAccess) player.connection).vss$connection()).vss$channel();
        return channel != null && channel.isActive() && channel.isWritable()
                && !BandwidthOptimizerCompat.isWaitingForClient(channel);
    }

    public static void sendQueuedToPlayer(ServerPlayer player, LegacyPayload payload, Runnable encoded) {
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

    private static boolean trySendToIntegratedHost(ServerPlayer player, LegacyPayload payload) {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return false;
        }
        Object handled = invokeClientHandler(
                "tryHandleIntegratedHostPayload",
                new Class<?>[] {ServerPlayer.class, LegacyPayload.class},
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