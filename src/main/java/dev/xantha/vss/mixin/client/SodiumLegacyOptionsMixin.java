package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.VSSVoxyOptionsIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sodium 0.5-0.7 have no built-in configuration registration API. */
@Pseudo
@Mixin(targets = {
        "me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI",
        "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI"
}, remap = false)
public abstract class SodiumLegacyOptionsMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void vss$addOptions(CallbackInfo ci) {
        VSSVoxyOptionsIntegration.addPage(this);
    }
}
