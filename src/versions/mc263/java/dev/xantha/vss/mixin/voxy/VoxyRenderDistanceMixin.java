package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyPredictionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Voxy 26.3 computes both clip distances once and passes them into viewport projection. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class VoxyRenderDistanceMixin {
    @ModifyArg(method = "setupViewport", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/VoxyRenderSystem;computeProjectionMat(Lme/cortex/voxy/client/core/RenderProperties;Lorg/joml/Matrix4fc;FF)Lorg/joml/Matrix4f;"),
            index = 3, require = 1)
    private float vss$projectionFarPlane(float original) {
        return VoxyPredictionBridge.farPlane(original);
    }

    // Keep Viewport's clip metadata equal to the matrix used by the mesher and depth blit.
    @ModifyArg(method = "setupViewport", at = @At(value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/Viewport;setProjection(Lorg/joml/Matrix4f;FF)Lme/cortex/voxy/client/core/rendering/Viewport;"),
            index = 2, require = 1)
    private float vss$viewportFarPlane(float original) {
        return VoxyPredictionBridge.farPlane(original);
    }
}
