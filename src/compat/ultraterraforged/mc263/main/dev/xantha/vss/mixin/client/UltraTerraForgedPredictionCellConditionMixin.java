package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.FreeTerraForgedSurfaceRules;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(targets = "com.pandaismyname1.ultraterraforged.world.worldgen.surface.condition.CellCondition", remap = false)
public abstract class UltraTerraForgedPredictionCellConditionMixin {
    @Unique private boolean vss$pointRules;
    @Unique private Object vss$surfaceContext;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void vss$context(@Coerce Object context, CallbackInfo ci) {
        vss$pointRules = FreeTerraForgedSurfaceRules.pointCondition(this);
        if (vss$pointRules) vss$surfaceContext = context;
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true, remap = false)
    private void vss$previewCell(CallbackInfoReturnable<Boolean> cir) {
        if (vss$pointRules) cir.setReturnValue(FreeTerraForgedSurfaceRules.test(this, vss$surfaceContext));
    }
}
