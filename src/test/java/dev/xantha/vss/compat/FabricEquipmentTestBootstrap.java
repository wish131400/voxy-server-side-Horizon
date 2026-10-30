package dev.xantha.vss.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/** Minimal Minecraft initialization for equipment inventory fixtures. */
final class FabricEquipmentTestBootstrap {
    private FabricEquipmentTestBootstrap() { }

    static void initialize() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
}
