package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionFogBridge;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve terrain fog before Voxy's lower-priority hook extends its render distance. */
@Mixin(value = FogRenderer.class, priority = 1100)
public abstract class PredictionFogCaptureMixin {
    @Inject(method = "setupFog", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;getDevice()Lcom/mojang/blaze3d/systems/GpuDevice;",
            remap = false))
    private void vss$captureTerrainFog(Camera camera, int renderDistance, DeltaTracker tracker,
                                      float partialTick, ClientLevel level, CallbackInfoReturnable<Vector4f> ci,
                                      @Local FogData data, @Local Vector4f color) {
        PredictionFogBridge.captureTerrain(camera, data, color);
    }
}
