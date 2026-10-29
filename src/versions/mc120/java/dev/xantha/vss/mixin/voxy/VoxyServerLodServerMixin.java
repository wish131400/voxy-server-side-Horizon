package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyServerLodServerCompat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Arbitrates only registered VSS players; other tkettela clients keep native streaming. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.network.ServerLodStreamer", remap = false)
public abstract class VoxyServerLodServerMixin {
    @Shadow @Final private static Map<UUID, ?> PLAYERS;
    @Shadow @Final private static Set<UUID> PENDING_ENABLE;
    @Unique private static final Set<UUID> vss$suppressed = new HashSet<>();

    @Inject(method = "tryEnable", at = @At("HEAD"), cancellable = true)
    private static void vss$selectTransport(MinecraftServer server, ServerPlayer player, CallbackInfo ci) {
        if (VoxyServerLodServerCompat.owns(player)) ci.cancel();
    }

    @Inject(method = "onRequest", at = @At("HEAD"), cancellable = true)
    private static void vss$ignoreSupersededRequests(MinecraftServer server, ServerPlayer player,
            ServerGamePacketListenerImpl handler, FriendlyByteBuf buf, PacketSender sender, CallbackInfo ci) {
        if (VoxyServerLodServerCompat.owns(player)) ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private static void vss$retireSupersededStreams(MinecraftServer server, CallbackInfo ci) {
        VoxyServerLodServerCompat.reconcile(server, PLAYERS, PENDING_ENABLE, vss$suppressed);
    }

    @Inject(method = "streamingPlayers", at = @At("RETURN"))
    private static void vss$nativeGenerationCenters(MinecraftServer server,
            CallbackInfoReturnable<List<ServerPlayer>> cir) {
        cir.getReturnValue().removeIf(VoxyServerLodServerCompat::owns);
    }

    @Inject(method = "stopStreaming", at = @At("HEAD"))
    private static void vss$discardNativeQueues(MinecraftServer server, CallbackInfo ci) {
        VoxyServerLodServerCompat.retireAll(PLAYERS);
        vss$suppressed.clear();
    }
}
