package dev.xantha.vss.mixin.ftbchunks;

import dev.xantha.vss.compat.ftbchunks.FTBChunksForceLoadCompat;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 26.1 FTB removed ExpectedImpl in favour of a platform force-load callback. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbchunks.fabric.FTBChunksFabric", remap = false)
public abstract class FTBChunksModernForceLoadMixin {
    @Redirect(method = "lambda$onInitialize$0", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setChunkForced(IIZ)Z", remap = true),
            require = 0, remap = false)
    private static boolean vss$budgetForceLoad(ServerLevel target, int x, int z, boolean add,
                                              ServerLevel level, UUID owner, int chunkX, int chunkZ,
                                              boolean force) {
        return FTBChunksForceLoadCompat.tryHandleVanilla(level, owner, chunkX, chunkZ, force)
                || target.setChunkForced(x, z, add);
    }
}
