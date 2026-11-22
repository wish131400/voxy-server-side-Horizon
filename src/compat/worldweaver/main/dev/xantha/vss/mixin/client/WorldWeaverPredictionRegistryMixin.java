package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.WorldWeaverBiomeContext;
import net.minecraft.core.RegistryAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = {"org.betterx.wover.state.api.WorldState", "de.ambertation.wover.state.api.WorldState"}, remap = false)
abstract class WorldWeaverPredictionRegistryMixin {
    @Inject(method = {"registryAccess", "allStageRegistryAccess"}, at = @At("HEAD"), cancellable = true, require = 0)
    private static void vss$usePredictionRegistries(CallbackInfoReturnable<RegistryAccess> callback) {
        RegistryAccess registries = WorldWeaverBiomeContext.activeRegistries();
        if (registries != null) callback.setReturnValue(registries);
    }
}
