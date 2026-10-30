package dev.xantha.vss.compat;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

/** 26.3 binds item components during world-data loading, after the static bootstrap used by unit tests. */
final class FabricEquipmentTestBootstrap {
    private FabricEquipmentTestBootstrap() { }

    static void initialize() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var stone = Items.STONE.builtInRegistryHolder();
        if (!stone.areComponentsBound()) {
            // This inventory fixture needs a real, stackable stone item; it does not load a server data pack.
            stone.bindComponents(DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 64).build());
        }
    }
}
