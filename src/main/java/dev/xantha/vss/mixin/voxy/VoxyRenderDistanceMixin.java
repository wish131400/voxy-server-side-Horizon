package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep the projection far plane beyond the complete VSS horizon on old and dynamic Voxy backends. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class VoxyRenderDistanceMixin {
    @Group(name = "vss$farPlane", min = 1)
    @ModifyVariable(method = "computeProjectionMat(Lorg/joml/Matrix4fc;F)Lorg/joml/Matrix4f;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static float vss$projectionFarPlane(float original) {
        return VoxyPredictionBridge.farPlane(original);
    }

    @Group(name = "vss$farPlane", min = 1)
    @ModifyVariable(method = "computeProjectionMat(Lme/cortex/voxy/client/core/RenderProperties;Lorg/joml/Matrix4fc;FF)Lorg/joml/Matrix4f;",
            at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private static float vss$projectionRangeFarPlane(float original) {
        return VoxyPredictionBridge.farPlane(original);
    }

    @Group(name = "vss$farPlane", min = 1)
    @ModifyConstant(method = "computeProjectionMat", constant = @Constant(floatValue = 48000.0F), require = 0)
    private static float vss$legacyFarPlane(float original) {
        return VoxyPredictionBridge.farPlane(original);
    }

    @Group(name = "vss$farPlane", min = 1)
    @Inject(method = "currentFarPlane()F", at = @At("RETURN"), cancellable = true, require = 0)
    private void vss$dynamicFarPlane(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(VoxyPredictionBridge.farPlane(cir.getReturnValue()));
    }

    @Group(name = "vss$farPlane", min = 1)
    @Inject(method = "getLodFarPlane()F", at = @At("RETURN"), cancellable = true, require = 0)
    private static void vss$staticFarPlane(CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(VoxyPredictionBridge.farPlane(cir.getReturnValue()));
    }
}
