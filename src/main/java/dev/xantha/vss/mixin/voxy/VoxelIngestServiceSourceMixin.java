package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyIngestControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks local chunk conversion while preserving VSS-scoped raw ingestion. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public abstract class VoxelIngestServiceSourceMixin {
    @Inject(
            method = "enqueueIngest",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void vss$gateLocalChunkIngest(CallbackInfoReturnable<Boolean> cir) {
        if (!VoxyIngestControl.isLocalChunkIngestionEnabled()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "rawIngest",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void vss$gateLocalRawIngest(CallbackInfoReturnable<Boolean> cir) {
        if (!VoxyIngestControl.isLocalChunkIngestionEnabled()
                && !VoxyIngestControl.isServerIngestActive()) {
            cir.setReturnValue(false);
        }
    }
}
