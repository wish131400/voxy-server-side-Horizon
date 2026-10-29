package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionFogBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Capture vanilla terrain fog before Voxy's priority-900 return hook extends it. */
@Mixin(value = FogRenderer.class, priority = 1100)
public abstract class PredictionFogCaptureMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void vss$captureTerrainFog(Camera camera, int renderDistance, DeltaTracker tracker,
                                      float partialTick, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
        FogData data = cir.getReturnValue();
        PredictionFogBridge.captureTerrain(camera, data, data.color);
    }
}
