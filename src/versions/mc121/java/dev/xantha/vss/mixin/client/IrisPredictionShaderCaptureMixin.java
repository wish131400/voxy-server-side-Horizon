package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionRenderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris 1.8 finishes applying its program by binding the draw framebuffer. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class IrisPredictionShaderCaptureMixin {
    // Unlike newer Iris, 1.8 has no iris$setupState. This Iris-owned method is
    // the last operation in ShaderInstance.apply, after samplers, uniforms and
    // blend modes, and avoids depending on a Minecraft method's mapped name.
    @Inject(method = "bindFramebuffer", at = @At("RETURN"), require = 0, remap = false)
    private void vss$shaderApplied(CallbackInfo ci) { PredictionRenderCapture.shaderApplied(this); }
}
