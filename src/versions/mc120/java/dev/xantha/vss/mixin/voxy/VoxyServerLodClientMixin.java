package dev.xantha.vss.mixin.voxy;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.xantha.vss.compat.VoxyServerLodClientCompat;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserves native-only servers, with one authoritative transport for VSS sessions. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.network.ClientLodReceiver", remap = false)
public abstract class VoxyServerLodClientMixin {
    @Shadow private static void closeSession() { throw new AssertionError(); }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void vss$pauseNativeRequests(Minecraft client, CallbackInfo ci) {
        if (VoxyServerLodClientCompat.pauseNativeReceiver()) {
            closeSession();
            ci.cancel();
        }
    }

    @WrapMethod(method = "onChunk")
    private static void vss$captureNativeColumn(Minecraft client, ClientPacketListener handler,
            FriendlyByteBuf buf, PacketSender sender, Operation<Void> original) {
        if (VoxyServerLodClientCompat.pauseNativeReceiver()) return;
        Object token = VoxyServerLodClientCompat.beginNativeColumn(client, handler, buf);
        boolean completed = false;
        try {
            original.call(client, handler, buf, sender);
            completed = true;
        } finally { VoxyServerLodClientCompat.finishNativeColumn(token, completed); }
    }

    @Redirect(method = "onChunk", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/common/world/service/VoxelIngestService;rawIngest"))
    private static boolean vss$ingestNativeSection(@Coerce Object engine, LevelChunkSection section,
            int x, int sy, int z, DataLayer block, DataLayer sky) throws Throwable {
        return VoxyServerLodClientCompat.ingestNativeSection(engine, section, x, sy, z, block, sky);
    }
}
