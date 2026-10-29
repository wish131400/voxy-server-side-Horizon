package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Suppresses only the real player that is currently represented by the remote snapshot. */
@Mixin(EntityRenderDispatcher.class)
public abstract class FarPlayerVisibilityMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void vss$skipDuplicateRemotePlayer(Entity entity, Frustum frustum, double x, double y, double z, float partialTick,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (FarPlayerClientRenderer.shouldSkipVanillaPlayer(entity)) cir.setReturnValue(false);
    }
}
