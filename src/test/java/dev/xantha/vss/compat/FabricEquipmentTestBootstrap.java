package dev.xantha.vss.compat;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

/** Minimal Minecraft initialization for equipment inventory fixtures. */
final class FabricEquipmentTestBootstrap {
    private FabricEquipmentTestBootstrap() { }

    static void initialize() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindFixtureItemComponents();
    }

    private static void bindFixtureItemComponents() {
        var stone = Items.STONE.builtInRegistryHolder();
        java.lang.reflect.Method componentsBound;
        try {
            componentsBound = stone.getClass().getMethod("areComponentsBound");
        } catch (NoSuchMethodException beforeDeferredItemComponents) {
            return;
        }
        try {
            if ((boolean) componentsBound.invoke(stone)) return;
            // 26.x binds item components during world-data loading. This offline inventory fixture
            // only needs a real stackable stone; reflection keeps the same test source usable on 1.20.1.
            Class<?> map = Class.forName("net.minecraft.core.component.DataComponentMap");
            Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType");
            Object maxStackSize = Class.forName("net.minecraft.core.component.DataComponents")
                    .getField("MAX_STACK_SIZE").get(null);
            Object builder = map.getMethod("builder").invoke(null);
            builder.getClass().getMethod("set", componentType, Object.class).invoke(builder, maxStackSize, 64);
            Object components = builder.getClass().getMethod("build").invoke(builder);
            stone.getClass().getMethod("bindComponents", map).invoke(stone, components);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not bind stone components for the equipment inventory fixture", failure);
        }
    }
}
