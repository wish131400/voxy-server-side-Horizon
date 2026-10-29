package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.VSSClientNetworking;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs frame retirement and budget bookkeeping even in menus or a paused world. */
@Mixin(GameRenderer.class)
public abstract class PredictionFrameMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void vss$beginFrame(CallbackInfo ci) {
        VSSClientNetworking.onRenderFrame();
    }
}
