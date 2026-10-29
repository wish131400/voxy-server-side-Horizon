package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionFogBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve terrain fog before Voxy's RETURN hook extends its render distance. */
@Mixin(FogRenderer.class)
public abstract class PredictionFogCaptureMixin {
    @Inject(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderFogShape(Lcom/mojang/blaze3d/shaders/FogShape;)V",
            shift = At.Shift.AFTER, remap = false))
    private static void vss$captureTerrainFog(Camera camera, FogRenderer.FogMode mode,
                                            float distance, boolean foggy, float partialTick, CallbackInfo ci) {
        if (mode == FogRenderer.FogMode.FOG_TERRAIN) PredictionFogBridge.captureTerrain(camera, foggy);
    }
}
