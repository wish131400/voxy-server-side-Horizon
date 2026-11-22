package dev.xantha.vss.client.prediction;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

final class LithostitchedWorldSeed {
    private LithostitchedWorldSeed() { }

    /** Mirrors Lithostitched's server-start seed binding on isolated snapshot values only. */
    static void bind(java.util.Map<ResourceKey<? extends Registry<?>>, ? extends Registry<?>> extraRegistries,
                     long seed) {
        var key = ResourceKey.createRegistryKey(Identifier.parse("lithostitched:fast_noise_config"));
        Registry<?> configs = extraRegistries.get(key);
        if (configs == null || configs.size() == 0) return;
        try {
            Object first = configs.iterator().next();
            Class<?> configType = Class.forName(
                    "dev.worldgen.lithostitched.api.worldgen.densityfunction.fastnoise.FastNoiseConfig",
                    false, first.getClass().getClassLoader());
            var bind = configType.getMethod("bind", long.class);
            for (Object config : configs) bind.invoke(configType.cast(config), seed);
        } catch (ReflectiveOperationException | ClassCastException failure) {
            throw new IllegalStateException("Cannot seed Lithostitched FastNoise snapshot", failure);
        }
    }
}
