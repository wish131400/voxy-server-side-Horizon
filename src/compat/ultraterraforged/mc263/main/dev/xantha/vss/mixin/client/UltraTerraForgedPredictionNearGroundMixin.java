package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.FreeTerraForgedSurfaceRules;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(targets = "com.pandaismyname1.ultraterraforged.world.worldgen.surface.condition.NearGroundCondition", remap = false)
public abstract class UltraTerraForgedPredictionNearGroundMixin {
    @Shadow @Final private int depth;
    @Unique private boolean vss$pointRules;
    @Unique private Object vss$surfaceContext;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void vss$context(@Coerce Object context, int depth, CallbackInfo ci) {
        vss$pointRules = FreeTerraForgedSurfaceRules.pointCondition(this);
        if (vss$pointRules) vss$surfaceContext = context;
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true, remap = false)
    private void vss$previewGround(CallbackInfoReturnable<Boolean> cir) {
        if (vss$pointRules) cir.setReturnValue(FreeTerraForgedSurfaceRules.nearGround(vss$surfaceContext, depth));
    }
}
