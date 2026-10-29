package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionRenderCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris 1.7 binds uniforms, textures and its final framebuffer inside ShaderInstance.apply. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ExtendedShader", remap = false)
public abstract class IrisPredictionShaderCaptureMixin {
    // Iris overrides a Minecraft method: Loom names it apply in development,
    // while the distributed Fabric JAR retains the intermediary method name.
    @Inject(method = {"apply()V", "method_34586()V"}, at = @At("RETURN"), require = 1, remap = false)
    private void vss$shaderApplied(CallbackInfo ci) { PredictionRenderCapture.shaderApplied(this); }
}
