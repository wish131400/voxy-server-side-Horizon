package dev.xantha.vss.mixin.minecraft;

import dev.xantha.vss.compat.ftbchunks.FTBChunksForceLoadCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keep vanilla persistence/tickets, omitting only FTB's eager synchronous materialization. */
@Mixin(ServerLevel.class)
public abstract class FTBChunksForceLoadNoBlockingMixin {
    @Redirect(method = "setChunkForced", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk vss$useForcedTicket(ServerLevel level, int x, int z) {
        return FTBChunksForceLoadCompat.skipSynchronousChunkRead(level, x, z)
                ? null : level.getChunk(x, z);
    }
}
