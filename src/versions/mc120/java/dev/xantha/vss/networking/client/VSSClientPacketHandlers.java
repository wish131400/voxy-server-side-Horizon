package dev.xantha.vss.networking.client;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.BatchResponseS2CPayload;
import dev.xantha.vss.networking.payloads.DirtyColumnsS2CPayload;
import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import dev.xantha.vss.networking.payloads.HandshakeRequestS2CPayload;
import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import dev.xantha.vss.networking.payloads.SessionConfigS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import dev.xantha.vss.networking.payloads.WorldgenProfileFragmentS2CPayload;
import dev.xantha.vss.networking.payloads.LostCityHintsS2CPayload;
import dev.xantha.vss.networking.payloads.StructureTemplateFragmentS2CPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import dev.xantha.vss.networking.payloads.LegacyPayload;
import net.minecraft.server.level.ServerPlayer;

public final class VSSClientPacketHandlers {
    private static final long INTEGRATED_HOST_DELIVERY_DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static volatile long lastIntegratedHostDeliveryDiagnosticNanos;

    private VSSClientPacketHandlers() {
    }

    public static void registerFabricReceivers() {
        dev.xantha.vss.networking.VSSNetworking.bindClientSender(payload ->
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(payload.type().id(),
                        dev.xantha.vss.networking.VSSNetworking.encode(payload)));
        net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking.registerGlobalReceiver(ServerIdentityS2CPayload.TYPE.id(),
                (client, handler, buffer, listenerAdder) -> {
                    handleServerIdentity(ServerIdentityS2CPayload.decode(buffer));
                    return java.util.concurrent.CompletableFuture.completedFuture(net.fabricmc.fabric.api.networking.v1.PacketByteBufs.empty());
                });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(SessionConfigS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = SessionConfigS2CPayload.decode(buffer); client.execute(() -> handleSessionConfig(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(BatchResponseS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = BatchResponseS2CPayload.decode(buffer); client.execute(() -> handleBatchResponse(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(DirtyColumnsS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = DirtyColumnsS2CPayload.decode(buffer); client.execute(() -> handleDirtyColumns(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(VoxelColumnS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = VoxelColumnS2CPayload.decode(buffer); client.execute(() -> handleVoxelColumn(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(FarPlayersS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = FarPlayersS2CPayload.decode(buffer); client.execute(() -> handleFarPlayers(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(HandshakeRequestS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = HandshakeRequestS2CPayload.decode(buffer); client.execute(() -> handleHandshakeRequest(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(WorldgenProfileS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = WorldgenProfileS2CPayload.decode(buffer); client.execute(() -> handleWorldgenProfile(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(WorldgenProfileFragmentS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = WorldgenProfileFragmentS2CPayload.decode(buffer); client.execute(() -> handleWorldgenFragment(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(LostCityHintsS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = LostCityHintsS2CPayload.STREAM_CODEC.decode(buffer); client.execute(() -> handleLostCityHints(payload));
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(StructureTemplateFragmentS2CPayload.TYPE.id(), (client, handler, buffer, response) -> {
            var payload = StructureTemplateFragmentS2CPayload.decode(buffer); client.execute(() -> handleStructureTemplateFragment(payload));
        });
    }

    public static boolean tryHandleIntegratedHostPayload(ServerPlayer player, LegacyPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || server != player.level().getServer()) {
            return false;
        }

        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null) {
            return false;
        }

        ServerPlayer integratedPlayer = server.getPlayerList().getPlayer(localPlayer.getUUID());
        if (integratedPlayer == null || integratedPlayer != player) {
            return false;
        }

        logIntegratedHostDelivery(payload);
        runOnClientThread(() -> handleDirectPayload(payload));
        return true;
    }

    public static void handleSessionConfig(SessionConfigS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleSessionConfig(payload));
    }

    public static void handleServerIdentity(ServerIdentityS2CPayload payload) {
        ClientConnectionIdentity.acceptServerIdentity(payload);
    }

    public static void handleBatchResponse(BatchResponseS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleBatchResponse(payload));
    }

    public static void handleDirtyColumns(DirtyColumnsS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleDirtyColumns(payload));
    }

    public static void handleVoxelColumn(VoxelColumnS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleVoxelColumn(payload));
    }

    public static void handleFarPlayers(FarPlayersS2CPayload payload) {
        runOnClientThread(() -> FarPlayerClientRenderer.handleFarPlayers(payload));
    }

    public static void handleHandshakeRequest(HandshakeRequestS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleHandshakeRequest(payload));
    }

    public static void handleWorldgenProfile(WorldgenProfileS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleWorldgenProfile(payload));
    }

    public static void handleWorldgenFragment(WorldgenProfileFragmentS2CPayload payload) {
        runOnClientThread(() -> VSSClientNetworking.handleWorldgenFragment(payload));
    }

    public static void handleLostCityHints(LostCityHintsS2CPayload payload) {
        runOnClientThread(() -> dev.xantha.vss.client.prediction.ClientPredictionState.onLostCityHints(payload));
    }

    public static void handleStructureTemplateFragment(StructureTemplateFragmentS2CPayload payload) {
        dev.xantha.vss.client.prediction.PredictionTemplateRepository.receive(payload);
    }

    private static void handleDirectPayload(LegacyPayload payload) {
        if (payload instanceof SessionConfigS2CPayload sessionConfig) {
            VSSClientNetworking.handleSessionConfig(sessionConfig);
        } else if (payload instanceof BatchResponseS2CPayload batchResponse) {
            VSSClientNetworking.handleBatchResponse(batchResponse);
        } else if (payload instanceof DirtyColumnsS2CPayload dirtyColumns) {
            VSSClientNetworking.handleDirtyColumns(dirtyColumns);
        } else if (payload instanceof VoxelColumnS2CPayload voxelColumn) {
            VSSClientNetworking.handleVoxelColumn(voxelColumn);
        } else if (payload instanceof FarPlayersS2CPayload farPlayers) {
            FarPlayerClientRenderer.handleFarPlayers(farPlayers);
        } else if (payload instanceof HandshakeRequestS2CPayload handshakeRequest) {
            VSSClientNetworking.handleHandshakeRequest(handshakeRequest);
        } else if (payload instanceof WorldgenProfileS2CPayload worldgenProfile) {
            VSSClientNetworking.handleWorldgenProfile(worldgenProfile);
        } else if (payload instanceof WorldgenProfileFragmentS2CPayload fragment) {
            VSSClientNetworking.handleWorldgenFragment(fragment);
        } else if (payload instanceof LostCityHintsS2CPayload hints) {
            dev.xantha.vss.client.prediction.ClientPredictionState.onLostCityHints(hints);
        } else if (payload instanceof StructureTemplateFragmentS2CPayload fragment) {
            handleStructureTemplateFragment(fragment);
        }
    }

    private static void runOnClientThread(Runnable task) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            task.run();
        } else {
            minecraft.execute(task);
        }
    }

    private static void logIntegratedHostDelivery(LegacyPayload payload) {
        if (!VSSLogger.isDebugEnabled()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastIntegratedHostDeliveryDiagnosticNanos < INTEGRATED_HOST_DELIVERY_DIAGNOSTIC_INTERVAL_NANOS) {
            return;
        }
        lastIntegratedHostDeliveryDiagnosticNanos = now;
        VSSLogger.debug("Integrated host direct S2C delivered: " + payload.getClass().getSimpleName());
    }
}
