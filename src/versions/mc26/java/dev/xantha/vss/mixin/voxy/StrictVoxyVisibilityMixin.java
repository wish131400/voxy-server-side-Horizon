package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.StrictLodVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe both the early 26.1 implicit-FBO and later explicit-texture Voxy render entry. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class StrictVoxyVisibilityMixin {
    @Inject(method = "shutdown()V", at = @At("HEAD"), require = 1)
    private void vss$closePredictionSource(CallbackInfo ci) {
        dev.xantha.vss.compat.VoxyPredictionBridge.renderClosed(this);
    }

    @Group(name = "vss$opaqueFrame", min = 1, max = 1)
    @Inject(method = "renderOpaque(Lme/cortex/voxy/client/core/rendering/Viewport;)V", at = @At("HEAD"), require = 0)
    private void vss$strictFrameLegacy(@Coerce Object viewport, CallbackInfo ci) {
        vss$strictFrameCommon(viewport, ci);
    }

    @Group(name = "vss$opaqueFrame", min = 1, max = 1)
    @Inject(method = "renderOpaque(Lme/cortex/voxy/client/core/rendering/Viewport;II)V", at = @At("HEAD"), require = 0)
    private void vss$strictFrameTextures(@Coerce Object viewport, int sourceDepth, int sourceColour, CallbackInfo ci) {
        vss$strictFrameCommon(viewport, ci);
    }

    @Unique
    private void vss$strictFrameCommon(Object viewport, CallbackInfo ci) {
        StrictLodVisibility.beginFrame(this, viewport);
        dev.xantha.vss.client.prediction.PredictionRenderer.observeUnifiedView(viewport);
    }
}
